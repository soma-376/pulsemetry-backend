package com.team376.pulsemetry.enrollment.management

import com.team376.pulsemetry.enrollment.auth.AbstractUserAuthApiTest
import com.team376.pulsemetry.enrollment.auth.AuthClockConfig
import com.team376.pulsemetry.enrollment.support.EnrollmentTestData
import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import tools.jackson.databind.JsonNode
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * 알림 확인 (ADR 0051 §6). 알림은 dashboard-api 의 평가 기록(`dashboard_cache.alerts`)이다 — 이 테스트는 그 앱의 마이그레이션 파일로 표를 만들고 행을 직접 넣는다.
 * 기대값: 그 조직의 임계값에 이른 알림만 확인할 수 있고(아니면 404), 판이 다르면 409, 이미 확인했으면 같은 기록을 돌려준다(멱등).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["pulsemetry.user-auth.enabled=true", "pulsemetry.management.enabled=true",
        "pulsemetry.management.onboarding-otlp-endpoint=https://ingest.example.test",
        "pulsemetry.management.response-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "pulsemetry.user-auth.allowed-origins=http://localhost:3000"])
@AutoConfigureMockMvc
@Import(PostgresContainerConfig::class, EnrollmentTestData::class, AuthClockConfig::class)
class AlertAcknowledgementApiTest : AbstractUserAuthApiTest() {

    @BeforeEach fun alertTable() {
        jdbc.sql("CREATE SCHEMA IF NOT EXISTS dashboard_cache").update()
        jdbc.sql(Files.readString(Path.of("../dashboard-api/src/main/resources/db/dashboard-cache/V5__dashboard_cache_alerts.sql"))).update()
        jdbc.sql("TRUNCATE dashboard_cache.alerts").update()
    }

    private fun json(response: HttpResponse<String>): JsonNode = mapper.readTree(response.body())
    private fun errorOf(response: HttpResponse<String>) = response.statusCode() to json(response).path("error").path("code").asString()
    private fun acknowledge(alertId: Any, expectedVersion: Long, token: String) =
        manage("POST", "/alerts/$alertId/acknowledge", mapOf("expectedVersion" to expectedVersion), token)

    private fun alert(organization: UUID, version: Long = 1, qualified: Boolean = true): UUID {
        val id = UUID.randomUUID()
        jdbc.sql("""INSERT INTO dashboard_cache.alerts (alert_id, tenant_id, rule_id, category, subject, window_key, status, qualified, occurred_at, last_seen_at,
                window_start, window_end, event_count, member_ids, summary, threshold_value, rule_version, version, created_at, updated_at)
            VALUES (:id, :t, 'model_not_allowed', 'security', :key, :key, 'open', :q, now(), now(), now(), now(), 1, '[]', '{}', 1, 1, :v, now(), now())""")
            .param("id", id).param("t", organization).param("key", id.toString()).param("q", qualified).param("v", version).update()
        return id
    }

    private fun acknowledgements(): List<String?> = jdbc.sql("SELECT alert_id || ':' || alert_version || ':' || acknowledged_by FROM enrollment.alert_acknowledgements")
        .query(String::class.java).list()

    @Test fun `그 조직의 알림을 지금 판으로 확인하고 다시 보내면 같은 기록을 돌려준다`() {
        val token = adminToken()
        val id = alert(tenant, version = 3)
        assertThat(errorOf(acknowledge(id, 2, token))).isEqualTo(409 to "version_conflict")
        assertThat(acknowledgements()).isEmpty()

        val first = acknowledge(id, 3, token)
        assertThat(first.statusCode()).withFailMessage(first.body()).isEqualTo(200)
        val body = json(first)
        assertThat(listOf(body.path("alertId").asString(), body.path("version").asLong(), body.path("acknowledgedBy").asString(), body.path("acknowledgedAt").asString()))
            .containsExactly(id.toString(), 3L, member.toString(), clock.now.toString())
        assertThat(acknowledgements()).containsExactly("$id:3:$member")

        // 이미 확인한 알림은 판이 달라도 같은 기록이다(그 사이 묶음이 늘었어도 확인됨으로 남는다).
        jdbc.sql("UPDATE dashboard_cache.alerts SET version = 4 WHERE alert_id = :id").param("id", id).update()
        clock.now = clock.now.plusSeconds(60)
        val again = json(acknowledge(id, 3, token))
        assertThat(again.path("acknowledgedAt").asString()).isEqualTo(body.path("acknowledgedAt").asString())
        assertThat(acknowledgements()).hasSize(1)
    }

    @Test fun `없는 알림·다른 조직의 알림·임계값에 이르지 않은 묶음은 404 이고, 구성원은 403 이다`() {
        val token = adminToken()
        val other = data.tenant().id
        assertThat(errorOf(acknowledge(alert(other), 1, token))).isEqualTo(404 to "not_found")
        assertThat(errorOf(acknowledge(alert(tenant, qualified = false), 1, token))).isEqualTo(404 to "not_found")
        assertThat(errorOf(acknowledge(UUID.randomUUID(), 1, token))).isEqualTo(404 to "not_found")
        assertThat(errorOf(acknowledge("not-a-uuid", 1, token))).isEqualTo(404 to "not_found")
        assertThat(errorOf(manage("POST", "/alerts/${alert(tenant)}/acknowledge", mapOf("expectedVersion" to -1), token))).isEqualTo(400 to "invalid_request")

        jdbc.sql("UPDATE enrollment.members SET role = 'member' WHERE id = :id").param("id", member).update()
        val memberToken = mapper.readTree(login().body()).path("access_token").asString()
        assertThat(errorOf(acknowledge(alert(tenant), 1, memberToken))).isEqualTo(403 to "forbidden")
        assertThat(acknowledgements()).isEmpty()
    }
}
