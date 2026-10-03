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

/**
 * 알림 규칙의 켜기·끄기와 모델·도구 목록의 교체 (ADR 0051 §1–§3). 평가·알림은 다루지 않는다.
 * 기대값은 ADR 의 표에서 쓴다: 근거가 없는 규칙은 켤 수 없고(사유), 한도 초과는 근거가 없어 언제나 켤 수 없다.
 * 판은 값이 바뀔 때만 오르고, 켜진 규칙이 기대는 목록은 비울 수 없다.
 */
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
    private fun list(listId: String, expectedVersion: Long, entries: List<Any?>, token: String) =
        manage("PUT", "/settings/alert-lists/$listId", mapOf("expectedVersion" to expectedVersion, "entries" to entries), token)
    private fun ok(response: HttpResponse<String>): JsonNode = response.also { assertThat(it.statusCode()).withFailMessage(it.body()).isEqualTo(200) }.let(::json)
    private fun ruleOf(body: JsonNode, ruleId: String): JsonNode = body.path("alertRules").toList().single { it.path("ruleId").asString() == ruleId }
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
            "model_not_allowed" to "allowed_models_not_configured",
            "tool_unapproved" to "approved_tools_not_configured",
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

    @Test fun `목록을 채우면 기대는 규칙을 켤 수 있고 판은 값이 바뀔 때만 오른다`() {
        val token = adminToken()
        val filled = ok(list("allowed_models", 0, listOf("gpt-6-astra", "claude-opus-*"), token))
        // 항목은 코드 포인트 순서로 돌려준다.
        assertThat(filled.at("/list/entries").toList().map { it.asString() }).containsExactly("claude-opus-*", "gpt-6-astra")
        assertThat(listOf(filled.at("/list/listId").asString(), filled.at("/list/version").asLong(), filled.at("/list/updatedAt").asString()))
            .containsExactly("allowed_models", 1L, clock.now.toString())
        assertThat(ruleOf(filled, "model_not_allowed").path("availability").asString()).isEqualTo("available")
        assertThat(ruleOf(filled, "model_not_allowed").path("reason").isNull).isTrue()
        assertThat(ruleOf(filled, "tool_unapproved").path("reason").asString()).isEqualTo("approved_tools_not_configured")
        // 같은 내용(순서만 다름)은 판을 올리지 않는다.
        assertThat(ok(list("allowed_models", 1, listOf("claude-opus-*", "gpt-6-astra"), token)).at("/list/version").asLong()).isEqualTo(1)

        clock.now = clock.now.plusSeconds(60)
        val on = ok(rule("model_not_allowed", 0, true, token))
        assertThat(listOf(on.path("ruleId").asString(), on.path("enabled").asBoolean(), on.path("version").asLong(), on.path("availability").asString()))
            .containsExactly("model_not_allowed", true, 1L, "available")
        assertThat(on.path("threshold").path("value").asDouble()).isEqualTo(1.0)
        assertThat(on.path("threshold").path("unit").asString()).isEqualTo("events")
        assertThat(ok(rule("model_not_allowed", 1, true, token)).path("version").asLong()).isEqualTo(1)
        assertThat(errorOf(rule("model_not_allowed", 0, false, token))).isEqualTo(409 to "version_conflict")
        assertThat(storedRules()).containsExactly("model_not_allowed:true:1:$member")

        // 승인 도구 목록도 같다.
        ok(list("approved_tools", 0, listOf("Bash", "Read", "mcp_tool"), token))
        assertThat(ok(rule("tool_unapproved", 0, true, token)).path("enabled").asBoolean()).isTrue()
        // 한도 초과는 목록과 무관하게 근거가 없다.
        assertThat(errorOf(rule("quota_exceeded", 0, true, token))).isEqualTo(422 to "alert_rule_unavailable")
    }

    @Test fun `켜진 규칙이 기대는 목록은 비울 수 없고 끈 뒤에는 비울 수 있다`() {
        val token = adminToken()
        ok(list("approved_tools", 0, listOf("Bash"), token))
        ok(rule("tool_unapproved", 0, true, token))

        val emptied = list("approved_tools", 1, emptyList<String>(), token)
        assertThat(errorOf(emptied)).isEqualTo(422 to "alert_list_in_use")
        assertThat(fieldOf(emptied)).containsExactly("entries")
        // 비우는 것만 막는다 — 다른 항목으로 바꾸는 것은 된다.
        assertThat(ok(list("approved_tools", 1, listOf("Read"), token)).at("/list/version").asLong()).isEqualTo(2)

        assertThat(ok(rule("tool_unapproved", 1, false, token)).path("version").asLong()).isEqualTo(2)
        val cleared = ok(list("approved_tools", 2, emptyList<String>(), token))
        assertThat(cleared.at("/list/entries").size()).isZero()
        assertThat(cleared.at("/list/version").asLong()).isEqualTo(3)
        assertThat(ruleOf(cleared, "tool_unapproved").path("reason").asString()).isEqualTo("approved_tools_not_configured")
        assertThat(errorOf(rule("tool_unapproved", 2, true, token))).isEqualTo(422 to "alert_rule_unavailable")
    }

    @Test fun `목록 항목은 형식을 지켜야 하고 없는 규칙·목록은 404 다`() {
        val token = adminToken()
        val invalid = listOf(
            listOf(""), listOf(" padded"), listOf("trailing "), listOf("bell\u0007"), listOf("a*b"), listOf("*"), listOf("x".repeat(201)),
            listOf("same", "same"), (1..201).map { "model-$it" }, listOf(1), listOf(null),
        )
        for (entries in invalid) {
            val response = list("allowed_models", 0, entries, token)
            assertThat(errorOf(response)).withFailMessage("$entries → ${response.body()}").isEqualTo(400 to "invalid_request")
            assertThat(fieldOf(response)).containsExactly("entries")
        }
        // 경계: 200자 항목 200개, 끝의 * 하나.
        val longest = (1..200).map { "m".repeat(196) + it.toString().padStart(3, '0') + "*" }
        assertThat(ok(list("allowed_models", 0, longest, token)).at("/list/entries").size()).isEqualTo(200)
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.organization_alert_list_entries").query(Int::class.java).single()).isEqualTo(200)

        assertThat(fieldOf(manage("PUT", "/settings/alert-lists/allowed_models", mapOf("entries" to listOf("a")), token))).containsExactly("expectedVersion")
        assertThat(fieldOf(manage("PATCH", "/settings/alert-rules/spend_spike", mapOf("expectedVersion" to 0, "enabled" to "yes"), token))).containsExactly("enabled")
        assertThat(errorOf(list("blocked_models", 0, listOf("a"), token))).isEqualTo(404 to "not_found")
        assertThat(errorOf(rule("budget_exceeded", 0, false, token))).isEqualTo(404 to "not_found")
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
            val builder = HttpRequest.newBuilder(URI("http://localhost:$port/api/v1/organizations/$organization/settings/alert-lists/allowed_models"))
                .header("Content-Type", "application/json")
            auth?.let { builder.header("Authorization", "Bearer $it") }
            return http.send(builder.PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(mapOf("expectedVersion" to 0, "entries" to listOf("a"))))).build(),
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

    @Test fun `패턴 경계 — 앞자리의 * 와 끝의 ** 는 거부하고, 부정 문법은 없어 ! 로 시작하는 항목은 그 글자 그대로 저장한다`() {
        val token = adminToken()
        // ADR 0051 §2: 끝의 * 하나만 접두사 일치다. 그 밖의 자리의 * 는 거부한다.
        for (entries in listOf(listOf("*claude"), listOf("claude**"), listOf("claude-*", "*"))) {
            val response = list("allowed_models", 0, entries, token)
            assertThat(errorOf(response)).withFailMessage("$entries → ${response.body()}").isEqualTo(400 to "invalid_request")
            assertThat(fieldOf(response)).containsExactly("entries")
        }
        val saved = ok(list("allowed_models", 0, listOf("!claude-opus-*", "claude-sonnet-*"), token))
        assertThat(saved.at("/list/entries").toList().map { it.asString() }).containsExactlyInAnyOrder("!claude-opus-*", "claude-sonnet-*")
    }
}
