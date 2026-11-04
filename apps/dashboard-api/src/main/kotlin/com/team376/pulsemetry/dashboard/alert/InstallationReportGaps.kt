package com.team376.pulsemetry.dashboard.alert

import com.team376.pulsemetry.persistence.enrollment.alert.AlertRuleState
import org.springframework.jdbc.core.simple.JdbcClient
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** 보고 부재는 사용량이나 손실량이 아니다. 마지막 서버 수신(없으면 등록)부터 168시간인 사건만 만든다. */
internal object InstallationReportGaps {
	const val RULE = "installation_report_gap"
	const val CATEGORY = "collection"
	const val VERSION = 1L
	val WINDOW: Duration = Duration.ofHours(168)

	data class Installation(val id: UUID, val memberId: UUID?, val deviceName: String?, val status: String,
		val createdAt: Instant, val lastReceivedAt: Instant?) {
		val basis: Instant get() = lastReceivedAt ?: createdAt
		val threshold: Instant get() = basis.plus(WINDOW)
		fun overdue(at: Instant) = status == "active" && !at.isBefore(threshold)
	}

	fun read(source: JdbcClient, tenant: UUID): List<Installation> = source.sql("""
		SELECT i.id, i.member_id, i.hostname, i.status, i.created_at, h.received_at
		FROM enrollment.installations i LEFT JOIN enrollment.installation_heartbeats h ON h.installation_id = i.id
		WHERE i.tenant_id = :tenant ORDER BY i.id
	""").param("tenant", tenant).query { rs, _ -> Installation(rs.getObject("id", UUID::class.java), rs.getObject("member_id", UUID::class.java),
		rs.getString("hostname"), rs.getString("status"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("received_at")?.toInstant()) }.list()

	fun hasActive(source: JdbcClient, tenant: UUID): Boolean = source.sql(
		"SELECT EXISTS (SELECT 1 FROM enrollment.installations WHERE tenant_id = :tenant AND status = 'active')")
		.param("tenant", tenant).query(Boolean::class.java).single()

	/** 설정 토글에 넣지 않는 시스템 규칙. 평가 상태 투영에서만 사용자 규칙에 더한다. */
	fun rule() = AlertRuleState(RULE, CATEGORY, true, VERSION, true, null, BigDecimal(168), "hours", "rolling_168_hours", null, null)

	/** 호출자는 cache 선점 트랜잭션을 보유한다. 종료·재발·평가 기록이 함께 커밋된다. */
	fun reconcile(store: AlertStore, tenant: UUID, installations: List<Installation>, now: Instant) {
		val byId = installations.associateBy { it.id.toString() }
		store.alerts(tenant).filter { it.ruleId == RULE && it.status == AlertEvaluator.OPEN }.forEach { alert ->
			val installation = byId[alert.subject]
			when {
				installation == null || installation.status != "active" -> store.resolveReportGap(alert, installation?.lastReceivedAt, "installation_revoked", now)
				installation.basis.toString() != alert.windowKey -> store.resolveReportGap(alert, installation.lastReceivedAt, "report_resumed", now)
			}
		}
		installations.filter { it.overdue(now) }.forEach { installation ->
			val subject = installation.id.toString()
			val key = installation.basis.toString()
			store.insert(StoredAlert(AlertEvaluator.alertId(tenant, RULE, subject, key), tenant, RULE, CATEGORY, subject, key,
				AlertEvaluator.OPEN, true, installation.threshold, installation.threshold, installation.basis, installation.threshold,
				0, listOfNotNull(installation.memberId?.toString()), linkedMapOf(
					"installationId" to subject, "deviceName" to installation.deviceName, "basisAt" to key,
					"thresholdReachedAt" to installation.threshold.toString(), "lastReceivedAt" to installation.lastReceivedAt?.toString(),
					"resolutionDetectedAt" to null, "resolutionReason" to null), 1), BigDecimal(168), VERSION, now)
		}
		store.record(tenant, Evaluation(RULE, VERSION, null, null, now, AlertEvaluator.EVALUATED, null, null, null))
	}
}
