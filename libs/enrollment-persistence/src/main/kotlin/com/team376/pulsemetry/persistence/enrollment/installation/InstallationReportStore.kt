package com.team376.pulsemetry.persistence.enrollment.installation

import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** 데몬이 보고한 수집 경로. `direct` 는 벤더 도구가 회사로 직접 보내 데몬이 전송을 보지 못한다. */
enum class CollectionMode(val wire: String) {
    LOCAL("local"),
    DIRECT("direct"),
    ;

    companion object {
        fun of(wire: String): CollectionMode? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 형식 검증이 끝난 보고 한 건. 시각([sentAt]·[receivingSince]·[lastDeliveredAt])은 **데몬 시계**의 값이다 —
 * 저장할 때 (받은 시각 − [sentAt])만큼 옮긴다. 개수는 데몬 프로세스([runId]) 단위 누적이다.
 */
data class InstallationReport(val sentAt: Instant, val daemonVersion: String, val architecture: String, val runId: String,
    val appliedConfigRevision: Int, val mode: CollectionMode, val receivingSince: Instant?, val forwarding: Boolean,
    val delivered: Long, val lost: Long, val pending: Long, val lastDeliveredAt: Instant?) {
    /** 회사로 가는 텔레메트리가 이 데몬을 지나고 있다 — 로컬 수신기가 듣고 상위 전달기가 돈다. */
    val collecting: Boolean get() = mode == CollectionMode.LOCAL && forwarding && receivingSince != null
}

/**
 * 설치 보고의 저장 (ADR 0040). 빈이 아니다 — 조립은 앱이 한다.
 *
 * 설치마다 마지막 보고 한 건(`installation_heartbeats`)과 수집 구간 이력(`installation_collection_segments`)을 쓴다.
 * 자기 트랜잭션을 열지 않는다 — 호출자가 설치 행을 잠근 트랜잭션 안에서 부른다. 같은 설치의 보고는 그 잠금으로 하나씩 처리된다.
 *
 * 구간은 "수집 중이었다고 보고로 확인된 시간"이다. 같은 프로세스가 손실 없이 이어 보고하면 구간을 늘리고,
 * 프로세스가 바뀌거나 수집이 끊겼다 이어지면 새 구간을 연다. 손실이 보고되면 직전 보고부터 이번 보고까지를 손실 구간으로 따로 남긴다 —
 * 손실이 없던 구간과 섞지 않는다.
 */
class InstallationReportStore(private val jdbc: JdbcClient, private val retention: Duration) {
    init {
        require(!retention.isNegative && !retention.isZero) { "수집 구간 이력의 보존 기간은 0보다 커야 한다" }
    }

    /**
     * [receivedAt] 에 받은 보고를 기록한다. [enrolledAt] 보다 앞선 수집을 주장하는 구간은 등록 시각에서 자른다.
     * [appliedManifestId] 는 보고한 판이 그 조직의 manifest 일 때만 준다.
     */
    fun record(installationId: UUID, enrolledAt: Instant, report: InstallationReport, appliedManifestId: UUID?, receivedAt: Instant) {
        // 데몬 시계의 절대값을 쓰지 않는다. 보낸 시각과의 차이만 쓴다.
        val offset = Duration.between(report.sentAt, receivedAt)
        val receivingSince = report.receivingSince?.plus(offset)
        val lastDeliveredAt = report.lastDeliveredAt?.plus(offset)
        val previous = previous(installationId)
        if (report.collecting) segment(installationId, enrolledAt, report, previous, requireNotNull(receivingSince), receivedAt)

        jdbc.sql("""INSERT INTO enrollment.installation_heartbeats(installation_id,received_at,run_id,daemon_version,architecture,applied_config_revision,
            applied_manifest_id,mode,forwarding,receiving_since,delivered,lost,pending,last_delivered_at)
            VALUES (:id,:received,:run,:version,:architecture,:revision,CAST(:manifest AS uuid),:mode,:forwarding,
                CAST(:since AS timestamptz),:delivered,:lost,:pending,CAST(:last AS timestamptz))
            ON CONFLICT (installation_id) DO UPDATE SET received_at=EXCLUDED.received_at,run_id=EXCLUDED.run_id,daemon_version=EXCLUDED.daemon_version,
                architecture=EXCLUDED.architecture,applied_config_revision=EXCLUDED.applied_config_revision,applied_manifest_id=EXCLUDED.applied_manifest_id,
                mode=EXCLUDED.mode,forwarding=EXCLUDED.forwarding,receiving_since=EXCLUDED.receiving_since,delivered=EXCLUDED.delivered,lost=EXCLUDED.lost,
                pending=EXCLUDED.pending,last_delivered_at=EXCLUDED.last_delivered_at""")
            .param("id", installationId).param("received", Timestamp.from(receivedAt)).param("run", report.runId).param("version", report.daemonVersion)
            .param("architecture", report.architecture).param("revision", report.appliedConfigRevision).param("manifest", appliedManifestId?.toString())
            .param("mode", report.mode.wire).param("forwarding", report.forwarding).param("since", receivingSince?.toString())
            .param("delivered", report.delivered).param("lost", report.lost).param("pending", report.pending).param("last", lastDeliveredAt?.toString())
            .update()

        jdbc.sql("DELETE FROM enrollment.installation_collection_segments WHERE installation_id=:id AND to_at<:cutoff")
            .param("id", installationId).param("cutoff", Timestamp.from(receivedAt.minus(retention))).update()
    }

    private fun segment(installationId: UUID, enrolledAt: Instant, report: InstallationReport, previous: Previous?, receivingSince: Instant,
        receivedAt: Instant) {
        val sameRun = previous != null && previous.runId == report.runId
        // 같은 프로세스인데 누적 개수가 줄었다 — 세대가 바뀐 것과 같다. 이전 보고와 잇지 않는다.
        val reset = sameRun && (report.lost < previous!!.lost || report.delivered < previous.delivered)
        val continuing = sameRun && !reset && previous!!.collecting
        val last = if (continuing) lastSegment(installationId, report.runId) else null
        // 수신기가 직전 확인 시각보다 뒤에 다시 떴다면 그 사이는 듣지 않았다.
        if (last != null && !receivingSince.isAfter(last.toAt)) {
            val lost = report.lost - previous!!.lost
            when {
                lost == 0L && last.lost == 0L -> extend(last.id, receivedAt, 0)
                lost > 0L && last.lost > 0L -> extend(last.id, receivedAt, lost)
                // 손실이 없던 구간과 있던 구간을 가른다. 손실은 직전 확인 시각과 이번 보고 사이에 있었다.
                else -> open(installationId, report.runId, last.toAt, receivedAt, lost)
            }
            return
        }
        val from = when {
            reset -> receivedAt
            sameRun -> maxOf(receivingSince, previous!!.receivedAt)
            else -> receivingSince
        }
        val lost = if (sameRun && !reset) report.lost - previous!!.lost else report.lost
        open(installationId, report.runId, minOf(maxOf(from, enrolledAt), receivedAt), receivedAt, lost)
    }

    private fun extend(id: UUID, toAt: Instant, lost: Long) {
        jdbc.sql("UPDATE enrollment.installation_collection_segments SET to_at=:to,lost=lost+:lost WHERE id=:id")
            .param("to", Timestamp.from(toAt)).param("lost", lost).param("id", id).update()
    }

    private fun open(installationId: UUID, runId: String, from: Instant, to: Instant, lost: Long) {
        jdbc.sql("INSERT INTO enrollment.installation_collection_segments(id,installation_id,run_id,from_at,to_at,lost) VALUES (:id,:installation,:run,:from,:to,:lost)")
            .param("id", UUID.randomUUID()).param("installation", installationId).param("run", runId)
            .param("from", Timestamp.from(from)).param("to", Timestamp.from(to)).param("lost", lost).update()
    }

    private fun previous(installationId: UUID): Previous? = jdbc.sql("""SELECT run_id,received_at,delivered,lost,
        (mode='local' AND forwarding AND receiving_since IS NOT NULL) AS collecting FROM enrollment.installation_heartbeats WHERE installation_id=:id""")
        .param("id", installationId).query { rs, _ ->
            Previous(rs.getString("run_id"), rs.getTimestamp("received_at").toInstant(), rs.getLong("delivered"), rs.getLong("lost"), rs.getBoolean("collecting"))
        }.optional().orElse(null)

    private fun lastSegment(installationId: UUID, runId: String): Segment? = jdbc.sql("""SELECT id,to_at,lost FROM enrollment.installation_collection_segments
        WHERE installation_id=:id AND run_id=:run ORDER BY to_at DESC, from_at DESC LIMIT 1""").param("id", installationId).param("run", runId)
        .query { rs, _ -> Segment(rs.getObject("id", UUID::class.java), rs.getTimestamp("to_at").toInstant(), rs.getLong("lost")) }.optional().orElse(null)

    private class Previous(val runId: String, val receivedAt: Instant, val delivered: Long, val lost: Long, val collecting: Boolean)
    private class Segment(val id: UUID, val toAt: Instant, val lost: Long)
}
