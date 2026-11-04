package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.authentication.DashboardPrincipal
import com.team376.pulsemetry.dashboard.authentication.Role
import com.team376.pulsemetry.dashboard.ingest.TransmissionLossReader
import com.team376.pulsemetry.dashboard.error.DashboardException
import com.team376.pulsemetry.dashboard.error.ErrorCode
import com.team376.pulsemetry.dashboard.request.PageRequest
import com.team376.pulsemetry.dashboard.support.AbstractDashboardApiTest
import com.team376.pulsemetry.dashboard.support.DashboardHttp
import com.team376.pulsemetry.dashboard.support.DashboardTestStores
import com.team376.pulsemetry.dashboard.support.SourceFixtures
import com.team376.pulsemetry.dashboard.support.TestDashboardAuthenticator
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.sql.Timestamp
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.UUID

class TransmissionLossApiTest : AbstractDashboardApiTest() {
    @Autowired private lateinit var losses: TransmissionLossReader
    private val writer get() = DashboardTestStores.writer

    private fun installation(tenant: UUID, status: String = "active") = SourceFixtures.insertInstallation(
        tenant, SourceFixtures.insertMember(tenant, "loss-${UUID.randomUUID()}@example.test"), status = status)
    private fun event(tenant: UUID, installation: UUID, at: Instant, count: Long) {
        writer.sql("""INSERT INTO enrollment.installation_loss_events(tenant_id,installation_id,run_id,payload_count,observed_at)
            VALUES (:tenant,:id,'run',:count,:at)""").param("tenant", tenant).param("id", installation)
            .param("count", count).param("at", Timestamp.from(at)).update()
    }
    private fun get(tenant: UUID, path: String = "", role: Role = Role.ADMIN, target: UUID = tenant) = http.send(
        "/api/v1/organizations/$target/ingest-status$path", headers = mapOf("Authorization" to
            TestDashboardAuthenticator.header(DashboardPrincipal(tenant, UUID.randomUUID(), role))))

    @Test fun `168시간 반개구간과 폐기 설치를 같은 기준으로 요약하고 페이지 조회한다`() {
        val tenant = DashboardTestStores.insertTenant()
        val live = installation(tenant)
        val revoked = installation(tenant, "revoked")
        val end = Instant.now().minusSeconds(1)
        val start = end.minus(Duration.ofHours(168))
        event(tenant, live, start, 2)
        event(tenant, live, start.minusSeconds(1), 100)
        event(tenant, live, end, 100)
        event(tenant, revoked, end.minusSeconds(10), 3)
        val other = DashboardTestStores.insertTenant()
        event(other, installation(other), end.minusSeconds(10), 500)
        assertThat(losses.summary(tenant, end).payloadCount).isEqualTo(5)
        assertThat(losses.summary(tenant, end).affectedInstallationCount).isEqualTo(2)
        val first = get(tenant, "/loss-installations?windowEnd=$end&limit=1")
        assertThat(first.statusCode()).describedAs(first.body()).isEqualTo(200)
        val one = DashboardHttp.json(first)
        assertThat(one.path("windowStart").asString()).isEqualTo(start.toString())
        assertThat(one.path("installations").path("totalCount").asInt()).isEqualTo(2)
        val cursor = one.path("installations").path("nextCursor").asString()
        val snapshot = one.path("meta").path("snapshotId").asString()
        val second = get(tenant, "/loss-installations?windowEnd=$end&limit=1&cursor=$cursor&snapshotId=$snapshot")
        assertThat(second.statusCode()).describedAs(second.body()).isEqualTo(200)
        val two = DashboardHttp.json(second)
        val rows = listOf(one, two).map { it.path("installations").path("items").path(0) }
        assertThat(rows.sumOf { it.path("payloadCount").asLong() }).isEqualTo(5)
        assertThat(rows.map { it.path("status").asString() }).containsExactlyInAnyOrder("active", "revoked")
        assertThat(two.path("installations").path("nextCursor").isNull).isTrue()
        assertThat(get(other, "/loss-installations?windowEnd=$end&cursor=$cursor").statusCode()).isEqualTo(409)
        assertThat(get(tenant, "/loss-installations?windowEnd=${end.minusSeconds(1)}&cursor=$cursor").statusCode()).isEqualTo(409)
    }

    @Test fun `관측 없는 0과 확인된 0 및 카운터 감소 제한을 구분한다`() {
        val tenant = DashboardTestStores.insertTenant()
        val id = installation(tenant)
        val end = Instant.now()
        val unavailable = losses.summary(tenant, end)
        assertThat(unavailable.availability).isEqualTo("unavailable")
        assertThat(unavailable.payloadCount).isNull()
        assertThat(unavailable.reasons).contains("no_observation")
        assertThat(get(tenant, "/loss-installations?windowEnd=$end").statusCode()).isEqualTo(503)
        writer.sql("""INSERT INTO enrollment.installation_loss_runs
            (installation_id,run_id,tenant_id,high_water_lost,first_observed_at,last_observed_at)
            VALUES (:id,'zero',:tenant,0,:at,:at)""").param("id", id).param("tenant", tenant).param("at", Timestamp.from(end.minusMillis(100))).update()
        val partial = losses.summary(tenant, end.plusMillis(1))
        assertThat(partial.payloadCount).isZero()
        assertThat(partial.availability).isEqualTo("partial")
        assertThat(partial.reasons).contains("history_incomplete")
        val empty = get(tenant, "/loss-installations?windowEnd=${partial.windowEnd}")
        assertThat(empty.statusCode()).describedAs(empty.body()).isEqualTo(200)
        assertThat(DashboardHttp.json(empty).path("installations").path("totalCount").asInt()).isZero()
        writer.sql("""INSERT INTO enrollment.installation_loss_limitations(tenant_id,installation_id,run_id,reason,observed_at)
            VALUES (:tenant,:id,'zero','counter_decreased',:at)""").param("tenant", tenant).param("id", id).param("at", Timestamp.from(end.minusMillis(50))).update()
        assertThat(losses.summary(tenant, end.plusMillis(2)).reasons).contains("counter_decreased")
    }

    @Test fun `추적 근거가 168시간보다 오래되면 보고 공백만으로 가용성을 낮추지 않는다`() {
        val tenant = DashboardTestStores.insertTenant()
        val id = installation(tenant)
        val end = Instant.now()
        val before = writer.sql("SELECT started_at FROM enrollment.installation_loss_tracking WHERE stopped_at IS NULL")
            .query { rs, _ -> rs.getTimestamp(1).toInstant() }.single()
        writer.sql("UPDATE enrollment.installation_loss_tracking SET started_at=:at WHERE stopped_at IS NULL")
            .param("at", Timestamp.from(end.minus(Duration.ofDays(20)))).update()
        try {
            writer.sql("""INSERT INTO enrollment.installation_loss_runs
                (installation_id,run_id,tenant_id,high_water_lost,first_observed_at,last_observed_at)
                VALUES (:id,'silent',:tenant,0,:at,:at)""").param("id", id).param("tenant", tenant)
                .param("at", Timestamp.from(end.minus(Duration.ofDays(10)))).update()
            assertThat(losses.summary(tenant, end).availability).isEqualTo("complete")
            assertThat(losses.summary(tenant, end).payloadCount).isZero()
        } finally {
            writer.sql("UPDATE enrollment.installation_loss_tracking SET started_at=:at WHERE stopped_at IS NULL")
                .param("at", Timestamp.from(before)).update()
        }
    }

    @Test fun `기존 수집 조회 실패가 손실 수량을 덮어쓰지 않으며 손실 조회 실패는 null이다`() {
        val tenant = DashboardTestStores.insertTenant()
        event(tenant, installation(tenant), Instant.now().minusSeconds(1), 7)
        writer.sql("ALTER TABLE telemetry_ops.tenant_ingest_summary RENAME TO tenant_ingest_summary_unavailable").update()
        try {
            val response = get(tenant)
            assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200)
            val body = DashboardHttp.json(response)
            assertThat(body.path("status").asString()).isEqualTo("unknown")
            assertThat(body.path("reason").asString()).isEqualTo("source_query_failed")
            assertThat(body.path("transmissionLoss").path("payloadCount").asLong()).isEqualTo(7)
        } finally { writer.sql("ALTER TABLE telemetry_ops.tenant_ingest_summary_unavailable RENAME TO tenant_ingest_summary").update() }
        writer.sql("ALTER TABLE enrollment.installation_loss_events RENAME TO installation_loss_events_unavailable").update()
        try {
            val response = get(tenant)
            assertThat(response.statusCode()).isEqualTo(200)
            val loss = DashboardHttp.json(response).path("transmissionLoss")
            assertThat(loss.path("payloadCount").isNull).isTrue()
            assertThat(loss.path("availability").asString()).isEqualTo("unavailable")
            assertThat(loss.path("reasons").path(0).asString()).isEqualTo("query_failed")
            val end = loss.path("windowEnd").asString()
            assertThat(get(tenant, "/loss-installations?windowEnd=$end").statusCode()).isEqualTo(503)
        } finally { writer.sql("ALTER TABLE enrollment.installation_loss_events_unavailable RENAME TO installation_loss_events").update() }
    }

    @Test fun `창 시작을 가로지른 중단도 부분 집계로 표시한다`() {
        val tenant = DashboardTestStores.insertTenant()
        val end = Instant.now()
        event(tenant, installation(tenant), end.minusSeconds(1), 4)
        val previous = writer.sql("SELECT id,started_at FROM enrollment.installation_loss_tracking WHERE stopped_at IS NULL")
            .query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getTimestamp(2).toInstant() }.single()
        val resumed = UUID.randomUUID()
        writer.sql("UPDATE enrollment.installation_loss_tracking SET started_at=:start,stopped_at=:stop WHERE id=:id")
            .param("id", previous.first).param("start", Timestamp.from(end.minus(Duration.ofDays(10))))
            .param("stop", Timestamp.from(end.minus(Duration.ofDays(8)))).update()
        try {
            writer.sql("INSERT INTO enrollment.installation_loss_tracking(id,started_at) VALUES (:id,:at)")
                .param("id", resumed).param("at", Timestamp.from(end.minus(Duration.ofDays(6)))).update()
            val result = losses.summary(tenant, end)
            assertThat(result.availability).isEqualTo("partial")
            assertThat(result.payloadCount).isEqualTo(4)
            assertThat(result.reasons).containsExactly("tracking_interrupted")
            assertThat(Instant.parse(result.availableFrom)).isEqualTo(end.minus(Duration.ofDays(6)).truncatedTo(java.time.temporal.ChronoUnit.MICROS))
        } finally {
            writer.sql("DELETE FROM enrollment.installation_loss_tracking WHERE id=:id").param("id", resumed).update()
            writer.sql("UPDATE enrollment.installation_loss_tracking SET started_at=:at,stopped_at=NULL WHERE id=:id")
                .param("id", previous.first).param("at", Timestamp.from(previous.second)).update()
        }
    }

    @Test fun `손실 상세도 관리자 권한과 조직 격리 및 요청 검증을 적용한다`() {
        val tenant = DashboardTestStores.insertTenant()
        val other = DashboardTestStores.insertTenant()
        val path = "/loss-installations?windowEnd=${Instant.now()}"
        assertThat(get(tenant, path, Role.MEMBER).statusCode()).isEqualTo(403)
        assertThat(get(tenant, path, target = other).statusCode()).isEqualTo(403)
        assertThat(get(tenant, "/loss-installations").statusCode()).isEqualTo(400)
        assertThat(get(tenant, "/loss-installations?windowEnd=bad").statusCode()).isEqualTo(400)
        assertThat(get(tenant, "/loss-installations?windowEnd=${Instant.now().plusSeconds(60)}").statusCode()).isEqualTo(400)
        assertThat(get(tenant, path).statusCode()).isEqualTo(409)
    }

    @Test fun `늦은 commit과 상태 변경은 같은 기준의 요약과 모든 상세 페이지를 바꾸지 않는다`() {
        val tenant = DashboardTestStores.insertTenant()
        val ids = List(3) { installation(tenant) }.sortedBy { it.toString() }
        val end = Instant.now().minusSeconds(1)
        event(tenant, ids[1], end.minusSeconds(5), 2)
        event(tenant, ids[2], end.minusSeconds(5), 3)
        val db = DashboardTestStores.postgres
        DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { connection ->
            connection.autoCommit = false
            connection.prepareStatement("""INSERT INTO enrollment.installation_loss_events
                (tenant_id,installation_id,run_id,payload_count,observed_at) VALUES (?,?,'late',100,?)""").use {
                it.setObject(1, tenant)
                it.setObject(2, ids[0])
                it.setTimestamp(3, Timestamp.from(end.minusSeconds(1)))
                it.executeUpdate()
            }
            val summary = losses.summary(tenant, end)
            assertThat(summary.payloadCount).isEqualTo(5)
            val firstResponse = get(tenant, "/loss-installations?windowEnd=$end&limit=1")
            assertThat(firstResponse.statusCode()).describedAs(firstResponse.body()).isEqualTo(200)
            val first = DashboardHttp.json(firstResponse)
            val cursor = first.path("installations").path("nextCursor").asString()
            connection.commit()
            writer.sql("UPDATE enrollment.installations SET status='revoked' WHERE id=:id").param("id", ids[2]).update()
            val secondResponse = get(tenant, "/loss-installations?windowEnd=$end&limit=1&cursor=$cursor")
            assertThat(secondResponse.statusCode()).describedAs(secondResponse.body()).isEqualTo(200)
            val second = DashboardHttp.json(secondResponse)
            val rows = listOf(first, second).map { it.path("installations").path("items").path(0) }
            assertThat(rows.sumOf { it.path("payloadCount").asLong() }).isEqualTo(5)
            assertThat(rows.map { it.path("installationId").asString() }).containsExactly(ids[1].toString(), ids[2].toString())
            assertThat(rows.map { it.path("status").asString() }).containsOnly("active")
            assertThat(losses.summary(tenant, end)).isEqualTo(summary)
            // 같은 PostgreSQL 마이크로초 안의 다른 기준도 문자열 키로 별개다.
            val newer = losses.summary(tenant, end.plusNanos(1))
            assertThat(newer.payloadCount).isEqualTo(105)
            assertThat(newer.affectedInstallationCount).isEqualTo(3)
            assertThatThrownBy { losses.installations(tenant, end, PageRequest(20, null), null, end.plusSeconds(600)) }
                .isInstanceOfSatisfying(DashboardException::class.java) { assertThat(it.code).isEqualTo(ErrorCode.SNAPSHOT_EXPIRED) }
        }
    }
}
