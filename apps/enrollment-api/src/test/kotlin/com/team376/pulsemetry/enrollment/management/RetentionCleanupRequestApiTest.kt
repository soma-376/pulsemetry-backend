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
import java.util.UUID

/**
 * 집계 보존 단축이 남기는 보존 정리 요청 (ADR 0047). 기대값은 ADR 의 규칙에서 쓴다:
 * 줄이면(무기한 → 유한 포함) 같은 트랜잭션에서 `retention_cleanup` 작업(대기)과 요청이 생기고 응답의 `cleanupOperationId` 가 그 작업이다.
 * 아직 한 번도 실행하지 않은 요청은 다음 저장이 대체한다(작업은 `superseded` 로 실패). 늘렸고 대체할 요청이 없으면 아무것도 없다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["pulsemetry.user-auth.enabled=true", "pulsemetry.management.enabled=true",
        "pulsemetry.management.onboarding-otlp-endpoint=https://ingest.example.test",
        "pulsemetry.management.response-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "pulsemetry.user-auth.allowed-origins=http://localhost:3000"])
@AutoConfigureMockMvc
@Import(PostgresContainerConfig::class, EnrollmentTestData::class, AuthClockConfig::class)
class RetentionCleanupRequestApiTest : AbstractUserAuthApiTest() {

    private lateinit var token: String
    private var settingsVersion = 0L

    @BeforeEach fun signIn() {
        jdbc.sql("TRUNCATE enrollment.retention_cleanup_requests, enrollment.operation_targets, enrollment.operations CASCADE").update()
        token = adminToken()
        settingsVersion = 0
    }

    /** 집계 보존만 바꾼다. 응답의 `cleanupOperationId`(없으면 null). */
    private fun retention(months: Int?): UUID? {
        val response = manage("PUT", "/collection-policy", mapOf("expectedVersion" to 3, "expectedSettingsVersion" to settingsVersion, "aggregateRetentionMonths" to months), token)
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200)
        val body: JsonNode = mapper.readTree(response.body())
        settingsVersion = body.path("settingsVersion").asLong()
        return body.path("cleanupOperationId").takeUnless { it.isNull }?.asString()?.let(UUID::fromString)
    }

    private data class Op(val kind: String, val status: String, val target: String, val targetStatus: String, val reason: String?, val requestedBy: UUID)
    private fun operation(id: UUID): Op = jdbc.sql("""SELECT o.kind,o.status,t.target_id,t.status AS target_status,t.reason,o.requested_by FROM enrollment.operations o
            JOIN enrollment.operation_targets t ON t.operation_id=o.id WHERE o.id=:id AND o.tenant_id=:tenant""")
        .param("id", id).param("tenant", tenant)
        .query { r, _ -> Op(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getObject(6, UUID::class.java)) }.single()
    private fun request(id: UUID): List<Any?> = jdbc.sql("SELECT retention_months,as_of,runs,outcome FROM enrollment.retention_cleanup_requests WHERE operation_id=:id")
        .param("id", id).query { r, _ -> listOf(r.getInt(1), r.getTimestamp(2).toInstant(), r.getInt(3), r.getString(4)) }.single()
    private fun open(): Int = jdbc.sql("SELECT count(*) FROM enrollment.retention_cleanup_requests WHERE closed_at IS NULL").query(Int::class.java).single()
    /** 보존 작업이 그 요청의 실행을 시작했다(선점). */
    private fun started(id: UUID) {
        jdbc.sql("UPDATE enrollment.retention_cleanup_requests SET runs=1 WHERE operation_id=:id").param("id", id).update()
        jdbc.sql("UPDATE enrollment.operations SET status='running' WHERE id=:id").param("id", id).update()
    }

    @Test fun `무기한에서 유한으로 줄이면 대기 중인 정리 작업과 요청이 생기고 응답이 그 작업을 가리킨다`() {
        val id = requireNotNull(retention(12))
        assertThat(operation(id)).isEqualTo(Op("retention_cleanup", "pending", "analysis_source", "pending", null, member))
        assertThat(request(id)).isEqualTo(listOf<Any?>(12, clock.now, 0, null))
        // 같은 값을 다시 보내면 설정이 바뀌지 않았다 — 요청도 그대로다.
        assertThat(retention(12)).isNull()
        assertThat(open()).isEqualTo(1)
        // 회수 기준만 바꾸면 보존과 무관하다.
        val reclaim = manage("PUT", "/collection-policy", mapOf("expectedVersion" to 3, "expectedSettingsVersion" to settingsVersion, "reclaimIdleDays" to 30), token)
        assertThat(mapper.readTree(reclaim.body()).path("cleanupOperationId").isNull).isTrue()
        assertThat(open()).isEqualTo(1)
    }

    @Test fun `실행 전의 요청은 다음 저장이 대체한다 — 늘려도 새 값으로 다시 요청하고 무기한이면 요청이 없다`() {
        val first = requireNotNull(retention(12))
        clock.now = clock.now.plusSeconds(60)
        val second = requireNotNull(retention(24))
        assertThat(operation(first)).isEqualTo(Op("retention_cleanup", "failed", "analysis_source", "failed", "superseded", member))
        assertThat(request(first)).isEqualTo(listOf<Any?>(12, clock.now.minusSeconds(60), 0, "superseded"))
        assertThat(request(second)).isEqualTo(listOf<Any?>(24, clock.now, 0, null))
        assertThat(operation(second).status).isEqualTo("pending")

        assertThat(retention(null)).isNull()
        assertThat(request(second)[3]).isEqualTo("superseded")
        assertThat(open()).isZero()
    }

    @Test fun `실행을 시작한 요청은 대체하지 않는다 — 줄이면 새 요청이 더 생기고 늘리면 없다`() {
        val first = requireNotNull(retention(36))
        started(first)
        val second = requireNotNull(retention(24))
        assertThat(operation(first).status).isEqualTo("running")
        assertThat(request(first)).isEqualTo(listOf<Any?>(36, clock.now, 1, null))
        assertThat(request(second)[0]).isEqualTo(24)
        assertThat(open()).isEqualTo(2)

        started(second)
        // 늘렸고 대체할 요청이 없다 — 지운 기록은 되돌리지 않으니 할 일이 없다.
        assertThat(retention(36)).isNull()
        assertThat(open()).isEqualTo(2)
    }
}
