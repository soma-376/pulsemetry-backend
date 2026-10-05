package com.team376.pulsemetry.enrollment.management

import com.team376.pulsemetry.enrollment.auth.AbstractUserAuthApiTest
import com.team376.pulsemetry.enrollment.auth.AuthClockConfig
import com.team376.pulsemetry.enrollment.support.EnrollmentTestData
import com.team376.pulsemetry.enrollment.secret.InvitationCode
import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import tools.jackson.databind.JsonNode
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

/** 등록 제품 기준 가용성·판 충돌·권한과 폐기한 목록 API를 검증한다(허브 ADR 0008). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["pulsemetry.user-auth.enabled=true", "pulsemetry.management.enabled=true",
        "pulsemetry.management.onboarding-otlp-endpoint=https://ingest.example.test",
        "pulsemetry.management.response-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "pulsemetry.user-auth.allowed-origins=http://localhost:3000"])
@AutoConfigureMockMvc
@Import(PostgresContainerConfig::class, EnrollmentTestData::class, AuthClockConfig::class)
class AlertRuleApiTest : AbstractUserAuthApiTest() {

    private fun json(response: HttpResponse<String>): JsonNode = mapper.readTree(response.body())
    private fun errorOf(response: HttpResponse<String>) = response.statusCode() to json(response).path("error").path("code").asString()
    private fun fieldOf(response: HttpResponse<String>) = json(response).path("error").path("fieldErrors").toList().map { it.path("field").asString() }
    private fun rule(ruleId: String, expectedVersion: Long, enabled: Boolean, token: String) =
        manage("PATCH", "/settings/alert-rules/$ruleId", mapOf("expectedVersion" to expectedVersion, "enabled" to enabled), token)
    private fun ok(response: HttpResponse<String>): JsonNode = response.also { assertThat(it.statusCode()).withFailMessage(it.body()).isEqualTo(200) }.let(::json)
    private fun storedRules(): List<String?> = jdbc.sql("SELECT rule_id || ':' || enabled || ':' || version || ':' || updated_by FROM enrollment.organization_alert_rules WHERE tenant_id = :t ORDER BY rule_id")
        .param("t", tenant).query(String::class.java).list()

    /** 설치 하나가 수집 구간을 보고했다(ADR 0040) — 완전한 날이 생길 수 있는 근거. */
    private fun reportedSegment(organization: UUID) {
        jdbc.sql("""INSERT INTO enrollment.installation_collection_segments (id, installation_id, run_id, from_at, to_at, lost)
            SELECT gen_random_uuid(), id, 'run-1', :from, :to, 0 FROM enrollment.installations WHERE tenant_id = :t LIMIT 1""")
            .param("t", organization).param("from", java.sql.Timestamp.from(clock.now.minusSeconds(3600))).param("to", java.sql.Timestamp.from(clock.now)).update()
    }

    @Test fun `저장한 적 없는 조직은 근거가 없는 규칙을 켤 수 없고 사유를 돌려준다`() {
        val token = adminToken()
        val expected = mapOf(
            "spend_spike" to "completeness_not_available",
            "quota_exceeded" to "source_not_available",
            "product_not_registered" to "registered_products_not_configured",
        )
        for ((ruleId, reason) in expected) {
            val response = rule(ruleId, 0, true, token)
            assertThat(errorOf(response)).withFailMessage(response.body()).isEqualTo(422 to "alert_rule_unavailable")
            assertThat(fieldOf(response)).containsExactly("enabled")
            assertThat(json(response).at("/details/reason").asString()).isEqualTo(reason)
        }
        // 끄기는 언제나 된다 — 이미 꺼져 있으면 판 그대로다.
        val off = ok(rule("quota_exceeded", 0, false, token))
        assertThat(listOf(off.path("enabled").asBoolean(), off.path("version").asLong(), off.path("availability").asString(), off.path("reason").asString()))
            .containsExactly(false, 0L, "unavailable", "source_not_available")
        assertThat(off.path("threshold").path("value").asDouble()).isEqualTo(5.0)
        assertThat(off.path("threshold").path("unit").asString()).isEqualTo("users")
        assertThat(off.path("evaluationWindow").asString()).isEqualTo("rolling_24_hours")
        assertThat(off.path("comparisonWindow").isNull).isTrue()
        assertThat(storedRules()).isEmpty()
    }

    private fun registerProduct(token: String): JsonNode {
        val response = manage("POST", "/vendors", mapOf("kind" to "claude_team", "displayName" to "Claude", "contract" to null), token)
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201)
        return json(response).path("vendor")
    }

    @Test fun `제품을 등록하면 규칙을 켤 수 있고 판은 값이 바뀔 때만 오른다`() {
        val token = adminToken()
        registerProduct(token)
        val on = ok(rule("product_not_registered", 0, true, token))
        assertThat(listOf(on.path("ruleId").asString(), on.path("enabled").asBoolean(), on.path("version").asLong(), on.path("availability").asString()))
            .containsExactly("product_not_registered", true, 1L, "available")
        assertThat(on.path("threshold").path("value").asDouble()).isEqualTo(1.0)
        assertThat(on.path("threshold").path("unit").asString()).isEqualTo("events")
        assertThat(ok(rule("product_not_registered", 1, true, token)).path("version").asLong()).isEqualTo(1)
        assertThat(errorOf(rule("product_not_registered", 0, false, token))).isEqualTo(409 to "version_conflict")
        assertThat(storedRules()).containsExactly("product_not_registered:true:1:$member")
    }

    @Test fun `마지막 등록 제품이 삭제되면 가용성이 사라져도 규칙을 끌 수 있다`() {
        val token = adminToken()
        val vendor = registerProduct(token)
        ok(rule("product_not_registered", 0, true, token))
        val vendorId = vendor.path("vendorId").asString()
        val version = vendor.path("version").asLong()
        val removed = manage("DELETE", "/vendors/$vendorId", null, token, etag = "\"vendor-$version\"")
        assertThat(removed.statusCode()).withFailMessage(removed.body()).isEqualTo(204)
        val off = ok(rule("product_not_registered", 1, false, token))
        assertThat(off.path("reason").asString()).isEqualTo("registered_products_not_configured")
        assertThat(off.path("version").asLong()).isEqualTo(2)
        assertThat(errorOf(rule("product_not_registered", 2, true, token))).isEqualTo(422 to "alert_rule_unavailable")
    }

    @Test fun `기존 목록과 모델·도구 규칙을 편집하지 못하고 잘못된 입력은 거절한다`() {
        val token = adminToken()
        for (id in listOf("model_not_allowed", "tool_unapproved", "budget_exceeded"))
            assertThat(errorOf(rule(id, 0, true, token))).isEqualTo(404 to "not_found")
        for (id in listOf("allowed_models", "approved_tools"))
            assertThat(manage("PUT", "/settings/alert-lists/$id", mapOf("expectedVersion" to 0, "entries" to listOf("a")), token).statusCode()).isEqualTo(404)
        assertThat(fieldOf(manage("PATCH", "/settings/alert-rules/product_not_registered", mapOf("enabled" to true), token))).containsExactly("expectedVersion")
        assertThat(fieldOf(manage("PATCH", "/settings/alert-rules/product_not_registered", mapOf("expectedVersion" to 0, "enabled" to "yes"), token))).containsExactly("enabled")
    }

    @Test fun `비용 급증은 그 조직의 설치가 수집 구간을 보고한 적이 있어야 켤 수 있다`() {
        val token = adminToken()
        // 다른 조직의 보고는 근거가 아니다.
        val other = data.tenant().id
        val otherMember = data.member(other, "owner@other.example.test").id
        val otherInvitation = data.invitation(other, otherMember, InvitationCode.generate())
        data.installation(other, otherMember, otherInvitation.id!!)
        reportedSegment(other)
        assertThat(json(rule("spend_spike", 0, true, token)).at("/details/reason").asString()).isEqualTo("completeness_not_available")

        assertThat(enroll().statusCode()).isIn(200, 201)
        reportedSegment(tenant)
        val on = ok(rule("spend_spike", 0, true, token))
        assertThat(listOf(on.path("enabled").asBoolean(), on.path("availability").asString(), on.path("evaluationWindow").asString(), on.path("comparisonWindow").asString()))
            .containsExactly(true, "available", "last_complete_7_calendar_days", "preceding_7_calendar_days")
        assertThat(on.path("threshold").path("value").asDouble()).isEqualTo(0.4)
        assertThat(on.path("threshold").path("unit").asString()).isEqualTo("ratio")
    }

    @Test fun `조직 경계와 권한 — 다른 조직의 경로는 404, 구성원은 403, 토큰이 없으면 401 이다`() {
        val token = adminToken()
        val other = data.tenant().id
        fun request(organization: UUID, auth: String?): HttpResponse<String> {
            val builder = HttpRequest.newBuilder(URI("http://localhost:$port/api/v1/organizations/$organization/settings/alert-rules/product_not_registered"))
                .header("Content-Type", "application/json")
            auth?.let { builder.header("Authorization", "Bearer $it") }
            return http.send(builder.method("PATCH", HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(mapOf("expectedVersion" to 0, "enabled" to false)))).build(),
                HttpResponse.BodyHandlers.ofString())
        }
        assertThat(errorOf(request(other, token))).isEqualTo(404 to "not_found")
        assertThat(request(tenant, null).statusCode()).isEqualTo(401)
        jdbc.sql("UPDATE enrollment.members SET role = 'member' WHERE id = :id").param("id", member).update()
        val memberToken = mapper.readTree(login().body()).path("access_token").asString()
        assertThat(errorOf(request(tenant, memberToken))).isEqualTo(403 to "forbidden")
        assertThat(errorOf(rule("spend_spike", 0, false, memberToken))).isEqualTo(403 to "forbidden")
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.organization_alert_lists").query(Int::class.java).single()).isZero()
    }


}
