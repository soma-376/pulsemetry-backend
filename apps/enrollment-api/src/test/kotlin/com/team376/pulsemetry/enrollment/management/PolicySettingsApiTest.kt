package com.team376.pulsemetry.enrollment.management

import com.team376.pulsemetry.enrollment.auth.AbstractUserAuthApiTest
import com.team376.pulsemetry.enrollment.auth.AuthClockConfig
import com.team376.pulsemetry.enrollment.support.EnrollmentTestData
import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import tools.jackson.databind.JsonNode
import java.net.http.HttpResponse

/**
 * 조직 정책 설정(회수 기준·집계 보존)의 저장 (ADR 0046) — 수집 정책 저장 명령(`PUT O/collection-policy`)의 확장.
 * 기대값은 ADR 의 규칙에서 쓴다: 보낸 필드만 바꾸고, 설정은 manifest 판을 올리지 않고 자기 판을 쓴다. 기존 `{expectedVersion, collectRawContent}` 본문은 그대로 동작한다.
 * 테스트 조직의 활성 manifest 는 판 3 이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["pulsemetry.user-auth.enabled=true", "pulsemetry.management.enabled=true",
        "pulsemetry.management.onboarding-otlp-endpoint=https://ingest.example.test",
        "pulsemetry.management.response-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "pulsemetry.user-auth.allowed-origins=http://localhost:3000"])
@AutoConfigureMockMvc
@Import(PostgresContainerConfig::class, EnrollmentTestData::class, AuthClockConfig::class)
class PolicySettingsApiTest : AbstractUserAuthApiTest() {

    private fun save(body: Map<String, Any?>, token: String): HttpResponse<String> = manage("PUT", "/collection-policy", body, token)
    private fun json(response: HttpResponse<String>): JsonNode = mapper.readTree(response.body())
    private fun ok(body: Map<String, Any?>, token: String): JsonNode = save(body, token).also { assertThat(it.statusCode()).withFailMessage(it.body()).isEqualTo(200) }.let(::json)
    private fun stored(): List<Any?>? = jdbc.sql("SELECT reclaim_idle_days,aggregate_retention_months,version,updated_at FROM enrollment.organization_policy_settings WHERE tenant_id=:tenant")
        .param("tenant", tenant).query { r, _ -> listOf(r.getObject(1)?.let { (it as Number).toInt() }, r.getObject(2)?.let { (it as Number).toInt() }, r.getLong(3), r.getTimestamp(4).toInstant()) }
        .optional().orElse(null)
    private fun manifests(): List<Pair<Int, Boolean>> = jdbc.sql("SELECT version,is_active FROM enrollment.manifests WHERE tenant_id=:tenant ORDER BY version")
        .param("tenant", tenant).query { r, _ -> r.getInt(1) to r.getBoolean(2) }.list()
    private fun assignments(): List<String?> = jdbc.sql("SELECT installation_id::text || ':' || manifest_id::text || ':' || coalesce(applied_at::text,'') FROM enrollment.installation_manifest_assignments")
        .query(String::class.java).list()
    private fun fieldOf(response: HttpResponse<String>) = json(response).path("error").path("fieldErrors").toList().map { it.path("field").asString() }

    @Test fun `기존 본문은 그대로 새 manifest 판을 만들고 설정은 저장 전 상태로 돌려준다`() {
        val token = adminToken()
        val body = ok(mapOf("expectedVersion" to 3, "collectRawContent" to true), token)
        assertThat(body.path("version").asInt()).isEqualTo(4)
        assertThat(body.path("collectRawContent").asBoolean()).isTrue()
        assertThat(body.path("confirmedAt").asString()).isEqualTo(clock.now.toString())
        assertThat(body.path("application").asString()).isEqualTo("future_enrollments")
        // 저장한 적 없는 설정: 판 0, 회수 기준은 조직 값 없음(null — 조회 서버의 기본 설정), 집계 보존은 무기한(null). 정리 작업은 없다.
        assertThat(listOf("reclaimIdleDays", "aggregateRetentionMonths", "settingsUpdatedAt", "cleanupOperationId").map { body.path(it).isNull }).containsOnly(true)
        assertThat(body.path("settingsVersion").asLong()).isZero()
        assertThat(stored()).isNull()
    }

    @Test fun `설정만 저장하면 manifest 판과 설치의 적용 상태는 그대로이고 설정의 판이 오른다`() {
        assertThat(enroll().statusCode()).isIn(200, 201)
        val token = adminToken()
        val before = manifests() to assignments()
        assertThat(before.second).isNotEmpty()

        val first = ok(mapOf("expectedVersion" to 3, "expectedSettingsVersion" to 0, "reclaimIdleDays" to 30), token)
        assertThat(first.path("version").asInt()).isEqualTo(3)
        assertThat(first.path("reclaimIdleDays").asInt()).isEqualTo(30)
        assertThat(first.path("aggregateRetentionMonths").isNull).isTrue()
        assertThat(first.path("settingsVersion").asLong()).isEqualTo(1)
        assertThat(first.path("settingsUpdatedAt").asString()).isEqualTo(clock.now.toString())
        // 원문 선택을 보내지 않았다 — 현재 manifest 의 선택을 그대로 알려 준다.
        assertThat(first.path("collectRawContent").asBoolean()).isFalse()
        assertThat(manifests() to assignments()).isEqualTo(before)

        // 하나만 보내면 나머지는 그대로다. null 은 무기한이다.
        clock.now = clock.now.plusSeconds(60)
        val second = ok(mapOf("expectedVersion" to 3, "expectedSettingsVersion" to 1, "aggregateRetentionMonths" to 24), token)
        assertThat(listOf(second.path("reclaimIdleDays").asInt(), second.path("aggregateRetentionMonths").asInt(), second.path("settingsVersion").asInt())).containsExactly(30, 24, 2)
        val reclaimOnly = ok(mapOf("expectedVersion" to 3, "expectedSettingsVersion" to 2, "reclaimIdleDays" to 60), token)
        assertThat(listOf(reclaimOnly.path("reclaimIdleDays").asInt(), reclaimOnly.path("aggregateRetentionMonths").asInt(), reclaimOnly.path("settingsVersion").asInt())).containsExactly(60, 24, 3)
        val third = ok(mapOf("expectedVersion" to 3, "expectedSettingsVersion" to 3, "aggregateRetentionMonths" to null), token)
        assertThat(third.path("aggregateRetentionMonths").isNull).isTrue()
        assertThat(third.path("reclaimIdleDays").asInt()).isEqualTo(60)
        assertThat(stored()).isEqualTo(listOf(60, null, 4L, clock.now))

        // 같은 값을 다시 보내면 판도 저장 시각도 그대로다.
        clock.now = clock.now.plusSeconds(60)
        val same = ok(mapOf("expectedVersion" to 3, "expectedSettingsVersion" to 4, "reclaimIdleDays" to 60, "aggregateRetentionMonths" to null), token)
        assertThat(same.path("settingsVersion").asLong()).isEqualTo(4)
        assertThat(stored()!![3]).isEqualTo(clock.now.minusSeconds(60))
        assertThat(manifests() to assignments()).isEqualTo(before)
    }

    @Test fun `원문 선택과 설정을 함께 보내면 한 트랜잭션이다 — 설정 판이 어긋나면 manifest 도 만들지 않는다`() {
        val token = adminToken()
        val conflict = save(mapOf("expectedVersion" to 3, "collectRawContent" to true, "expectedSettingsVersion" to 5, "reclaimIdleDays" to 14), token)
        assertThat(conflict.statusCode()).isEqualTo(409)
        assertThat(json(conflict).path("error").path("code").asString()).isEqualTo("version_conflict")
        assertThat(fieldOf(conflict)).containsExactly("expectedSettingsVersion")
        assertThat(manifests().last()).isEqualTo(3 to true)
        assertThat(stored()).isNull()

        val both = ok(mapOf("expectedVersion" to 3, "collectRawContent" to true, "expectedSettingsVersion" to 0, "reclaimIdleDays" to 14, "aggregateRetentionMonths" to 12), token)
        assertThat(listOf(both.path("version").asInt(), both.path("reclaimIdleDays").asInt(), both.path("aggregateRetentionMonths").asInt(), both.path("settingsVersion").asInt()))
            .containsExactly(4, 14, 12, 1)
        // manifest 판이 어긋나면 설정만 보내도 거부한다(같은 정책 리소스의 판이다).
        assertThat(save(mapOf("expectedVersion" to 3, "expectedSettingsVersion" to 1, "reclaimIdleDays" to 7), token).statusCode()).isEqualTo(409)
        assertThat(save(mapOf("expectedVersion" to 4, "expectedSettingsVersion" to 0, "reclaimIdleDays" to 7), token).statusCode()).isEqualTo(409)
        assertThat(stored()!!.take(3)).isEqualTo(listOf<Any?>(14, 12, 1L))
    }

    @Test fun `허용 밖의 값·판 없는 설정·바꿀 것이 없는 본문은 400 이고 아무것도 저장하지 않는다`() {
        val token = adminToken()
        val cases = listOf(
            mapOf("expectedVersion" to 3, "expectedSettingsVersion" to 0, "reclaimIdleDays" to 10) to "reclaimIdleDays",
            mapOf("expectedVersion" to 3, "expectedSettingsVersion" to 0, "reclaimIdleDays" to "30") to "reclaimIdleDays",
            mapOf("expectedVersion" to 3, "expectedSettingsVersion" to 0, "reclaimIdleDays" to null) to "reclaimIdleDays",
            mapOf("expectedVersion" to 3, "expectedSettingsVersion" to 0, "aggregateRetentionMonths" to 6) to "aggregateRetentionMonths",
            mapOf("expectedVersion" to 3, "expectedSettingsVersion" to 0, "aggregateRetentionMonths" to 12.5) to "aggregateRetentionMonths",
            mapOf("expectedVersion" to 3, "reclaimIdleDays" to 30) to "expectedSettingsVersion",
            mapOf("expectedVersion" to 3, "expectedSettingsVersion" to -1, "reclaimIdleDays" to 30) to "expectedSettingsVersion",
            mapOf("expectedVersion" to 3) to null,
            mapOf("expectedVersion" to 3, "collectRawContent" to "true") to null,
        )
        for ((body, field) in cases) {
            val response = save(body, token)
            assertThat(response.statusCode()).describedAs(body.toString()).isEqualTo(400)
            assertThat(json(response).path("error").path("code").asString()).isEqualTo("invalid_request")
            assertThat(fieldOf(response)).describedAs(body.toString()).isEqualTo(listOfNotNull(field))
        }
        assertThat(stored()).isNull()
        assertThat(manifests()).containsExactly(3 to true)
    }
}
