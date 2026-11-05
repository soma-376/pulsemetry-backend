package com.team376.pulsemetry.persistence.enrollment.installation

import com.team376.pulsemetry.domain.enrollment.installation.LossCounter
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** 설치 행 잠금과 같은 트랜잭션에서만 호출한다. 전달 관측과 수집 구간의 완전성은 별개다(ADR 0076). */
class InstallationLossStore(private val jdbc: JdbcClient, retention: Duration) {
    // 헤더의 기준 시각으로 10분간 상세 페이지를 이어 읽을 수 있으므로 그 유효기간도 보존한다.
    private val retention = maxOf(retention, Duration.ofHours(168).plusMinutes(10))

    fun record(installationId: UUID, report: InstallationReport, receivedAt: Instant) {
        // rollback 중단 명령과 직렬화한다. 추적이 중단된 채 새 writer가 보고만 갱신해서는 안 된다.
        if (!jdbc.sql("SELECT id FROM enrollment.installation_loss_tracking WHERE stopped_at IS NULL FOR SHARE")
            .query(UUID::class.java).optional().isPresent) throw InstallationLossTrackingPaused()
        if (report.mode == CollectionMode.LOCAL && report.forwarding) observe(installationId, report, receivedAt)
        for (table in listOf("installation_loss_events", "installation_loss_limitations")) {
            jdbc.sql("DELETE FROM enrollment.$table WHERE installation_id=:id AND observed_at<:cutoff")
                .param("id", installationId).param("cutoff", Timestamp.from(receivedAt.minus(retention))).update()
        }
    }

    private fun observe(installationId: UUID, report: InstallationReport, receivedAt: Instant) {
        val tenant = jdbc.sql("SELECT tenant_id FROM enrollment.installations WHERE id=:id")
            .param("id", installationId).query(UUID::class.java).single()
        val previous = jdbc.sql("SELECT high_water_lost,historical FROM enrollment.installation_loss_runs WHERE installation_id=:id AND run_id=:run")
            .param("id", installationId).param("run", report.runId)
            .query { rs, _ -> rs.getLong("high_water_lost") to rs.getBoolean("historical") }.optional().orElse(null)
        // 과거 구간에서만 알려진 실행의 누적값은 복원할 수 없다. 재생을 새로운 손실로 세지 않는다.
        if (previous?.second == true) return
        val change = LossCounter.observe(previous?.first ?: 0L, report.lost)
        jdbc.sql("""INSERT INTO enrollment.installation_loss_runs
            (installation_id,run_id,tenant_id,high_water_lost,first_observed_at,last_observed_at)
            VALUES (:id,:run,:tenant,:high,:at,:at)
            ON CONFLICT (installation_id,run_id) DO UPDATE SET high_water_lost=EXCLUDED.high_water_lost,
                first_observed_at=COALESCE(installation_loss_runs.first_observed_at,EXCLUDED.first_observed_at),last_observed_at=EXCLUDED.last_observed_at""")
            .param("id", installationId).param("run", report.runId).param("tenant", tenant)
            .param("high", change.highWater).param("at", Timestamp.from(receivedAt)).update()
        if (change.delta > 0) jdbc.sql("""INSERT INTO enrollment.installation_loss_events
            (tenant_id,installation_id,run_id,payload_count,observed_at) VALUES (:tenant,:id,:run,:count,:at)""")
            .param("tenant", tenant).param("id", installationId).param("run", report.runId)
            .param("count", change.delta).param("at", Timestamp.from(receivedAt)).update()
        if (change.decreased) jdbc.sql("""INSERT INTO enrollment.installation_loss_limitations
            (tenant_id,installation_id,run_id,reason,observed_at) VALUES (:tenant,:id,:run,'counter_decreased',:at)""")
            .param("tenant", tenant).param("id", installationId).param("run", report.runId).param("at", Timestamp.from(receivedAt)).update()
    }
}
