package com.team376.pulsemetry.dashboard.ingest

import com.team376.pulsemetry.dashboard.analytics.CurrentStateTokens
import com.team376.pulsemetry.dashboard.analytics.Page
import com.team376.pulsemetry.dashboard.error.DashboardException
import com.team376.pulsemetry.dashboard.error.ErrorCode
import com.team376.pulsemetry.dashboard.error.FieldErrorCode
import com.team376.pulsemetry.dashboard.request.PageCursor
import com.team376.pulsemetry.dashboard.request.PageCursorCodec
import com.team376.pulsemetry.dashboard.request.PageRequest
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** 손실은 RDS 원장만 읽는다. ClickHouse·분석 기간·설치의 현재 폐기 여부는 집계 입력이 아니다. */
class TransmissionLossReader(private val source: JdbcClient, private val codec: PageCursorCodec, private val tokens: CurrentStateTokens) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun summary(tenant: UUID, end: Instant): TransmissionLoss = try {
        val start = end.minus(WINDOW)
        val epochs = source.sql("SELECT started_at,stopped_at FROM enrollment.installation_loss_tracking WHERE started_at<:end ORDER BY started_at")
            .param("end", Timestamp.from(end)).query { rs, _ -> rs.getTimestamp(1).toInstant() to rs.getTimestamp(2)?.toInstant() }.list()
        if (epochs.isEmpty()) unavailable(end, "tracking_not_started") else {
            val available = maxOf(start, epochs.last().first)
            val count = source.sql("""SELECT COALESCE(sum(payload_count),0),count(DISTINCT installation_id)
                FROM enrollment.installation_loss_events WHERE tenant_id=:tenant AND observed_at>=:start AND observed_at<:end""")
                .param("tenant", tenant).param("start", Timestamp.from(start)).param("end", Timestamp.from(end))
                .query { rs, _ -> rs.getLong(1) to rs.getLong(2) }.single()
            val observed = count.first > 0 || source.sql("""SELECT EXISTS(SELECT 1 FROM enrollment.installation_loss_runs
                WHERE tenant_id=:tenant AND NOT historical AND first_observed_at<:end AND last_observed_at>=:tracked)""")
                .param("tenant", tenant).param("end", Timestamp.from(end)).param("tracked", Timestamp.from(epochs.last().first))
                .query(Boolean::class.javaObjectType).single()
            val reasons = mutableListOf<String>()
            if (epochs.first().first > start) reasons += "history_incomplete"
            if (epochs.withIndex().any { (index, epoch) ->
                val stopped = epoch.second
                val resumed = epochs.getOrNull(index + 1)?.first ?: end
                stopped != null && stopped < end && resumed > start && resumed > stopped
            }) reasons += "tracking_interrupted"
            reasons += source.sql("""SELECT DISTINCT reason FROM enrollment.installation_loss_limitations
                WHERE tenant_id=:tenant AND observed_at>=:start AND observed_at<:end ORDER BY reason""")
                .param("tenant", tenant).param("start", Timestamp.from(start)).param("end", Timestamp.from(end)).query(String::class.java).list().filterNotNull()
            if (!observed) reasons += "no_observation"
            TransmissionLoss(if (observed) count.first else null, start.toString(), end.toString(), available.toString(),
                if (!observed) "unavailable" else if (reasons.isEmpty()) "complete" else "partial", reasons, if (observed) count.second else null)
        }
    } catch (e: RuntimeException) {
        log.warn("손실 증가분 조회 실패 — 집계 불가로 반환한다", e)
        unavailable(end, "query_failed")
    }

    fun installations(tenant: UUID, end: Instant, page: PageRequest, snapshotId: String?, now: Instant): LossInstallationsResponse {
        if (end > now || end <= now.minus(Duration.ofMinutes(10))) throw DashboardException.invalid("windowEnd", FieldErrorCode.OUT_OF_RANGE)
        val kind = "transmission-loss:$end"
        val token = tokens.resolve(kind, tenant, snapshotId ?: page.cursor?.snapshotId, now)
        val scope = "loss-installations:$tenant:$end"
        page.cursor?.let {
            if (it.snapshotId != token.value || it.scope != scope || it.after.size != 1) throw invalidCursor()
        }
        val after = page.cursor?.let { cursor ->
            cursor.after.single()?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: throw invalidCursor()
        }
        val start = end.minus(WINDOW)
        val rows = source.sql("""SELECT e.installation_id,i.hostname,i.member_id,m.email,i.status::text,
            sum(e.payload_count) AS payload_count,max(e.observed_at) AS last_observed_at
            FROM enrollment.installation_loss_events e
            JOIN enrollment.installations i ON i.id=e.installation_id AND i.tenant_id=e.tenant_id
            LEFT JOIN enrollment.members m ON m.id=i.member_id AND m.tenant_id=e.tenant_id
            WHERE e.tenant_id=:tenant AND e.observed_at>=:start AND e.observed_at<:end
            GROUP BY e.installation_id,i.hostname,i.member_id,m.email,i.status ORDER BY e.installation_id""")
            .param("tenant", tenant).param("start", Timestamp.from(start)).param("end", Timestamp.from(end))
            .query { rs, _ -> LossInstallation(rs.getString("installation_id"), rs.getString("hostname"), rs.getString("member_id"),
                rs.getString("email"), rs.getLong("payload_count"), rs.getTimestamp("last_observed_at").toInstant().toString(), rs.getString("status")) }.list()
        val offset = after?.let { id -> rows.indexOfFirst { it.installationId == id.toString() }.takeIf { it >= 0 }?.plus(1) ?: throw invalidCursor() } ?: 0
        val items = rows.drop(offset).take(page.limit)
        val next = if (offset + items.size < rows.size) codec.encode(PageCursor(token.value, scope, listOf(items.last().installationId))) else null
        return LossInstallationsResponse(LossMeta(tenant.toString(), token.value, end.toString()), start.toString(), end.toString(), Page(items, rows.size, next))
    }

    private fun unavailable(end: Instant, reason: String) = TransmissionLoss(null, end.minus(WINDOW).toString(), end.toString(), null, "unavailable", listOf(reason), null)
    private fun invalidCursor() = DashboardException.invalid("cursor", FieldErrorCode.INVALID_CURSOR)

    companion object { val WINDOW: Duration = Duration.ofHours(168) }
}

data class TransmissionLoss(val payloadCount: Long?, val windowStart: String, val windowEnd: String, val availableFrom: String?,
    val availability: String, val reasons: List<String>, val affectedInstallationCount: Long?)
data class LossInstallation(val installationId: String, val deviceName: String?, val memberId: String?, val account: String?,
    val payloadCount: Long, val lastObservedAt: String, val status: String)
data class LossMeta(val organizationId: String, val snapshotId: String, val asOf: String)
data class LossInstallationsResponse(val meta: LossMeta, val windowStart: String, val windowEnd: String, val installations: Page<LossInstallation>)
