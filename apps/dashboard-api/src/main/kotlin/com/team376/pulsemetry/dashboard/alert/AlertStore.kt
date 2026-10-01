package com.team376.pulsemetry.dashboard.alert

import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** 저장된 알림 하나 (ADR 0051 §6). [subject] 는 급증이면 빈 문자열, 24시간 규칙이면 모델·도구 이름이다. */
data class StoredAlert(
	val alertId: UUID,
	val tenantId: UUID,
	val ruleId: String,
	val category: String,
	val subject: String,
	val windowKey: String,
	val status: String,
	val qualified: Boolean,
	val occurredAt: Instant,
	val lastSeenAt: Instant,
	val windowStart: Instant,
	val windowEnd: Instant,
	val eventCount: Long,
	val memberIds: List<String>,
	val summary: Map<String, Any?>,
	val version: Long,
)

/** 규칙의 마지막 평가와 다음 평가의 시작점. */
data class Evaluation(
	val ruleId: String,
	val ruleVersion: Long,
	val cursorAt: Instant?,
	val cursorDate: LocalDate?,
	val evaluatedAt: Instant,
	val status: String,
	val reason: String?,
	val windowStart: Instant?,
	val windowEnd: Instant?,
)

/**
 * 알림 평가 기록의 저장 (ADR 0051 §5). 이 앱의 캐시 계정으로 RDS `dashboard_cache` 에 쓴다 — 원천(분석 행·enrollment)은 읽기만 한다.
 * 알림의 확인은 여기 없다(enrollment-api 의 명령이 enrollment 스키마에 쓴다).
 */
class AlertStore(private val cache: JdbcClient, private val mapper: ObjectMapper) {

	/** 조직의 평가를 선점한다. 다른 인스턴스의 선점이 기한 안이면 false. */
	fun claim(tenant: UUID, owner: String, now: Instant, until: Instant): Boolean =
		cache.sql("""INSERT INTO dashboard_cache.alert_evaluation_leases (tenant_id, claimed_by, claimed_until) VALUES (:tenant, :owner, :until)
				ON CONFLICT (tenant_id) DO UPDATE SET claimed_by = EXCLUDED.claimed_by, claimed_until = EXCLUDED.claimed_until
				WHERE alert_evaluation_leases.claimed_until < :now OR alert_evaluation_leases.claimed_by = :owner
				RETURNING tenant_id""")
			.param("tenant", tenant).param("owner", owner).param("until", Timestamp.from(until)).param("now", Timestamp.from(now))
			.query(UUID::class.java).optional().isPresent

	fun release(tenant: UUID, owner: String) {
		cache.sql("DELETE FROM dashboard_cache.alert_evaluation_leases WHERE tenant_id = :tenant AND claimed_by = :owner")
			.param("tenant", tenant).param("owner", owner).update()
	}

	fun evaluation(tenant: UUID, ruleId: String): Evaluation? =
		cache.sql("SELECT * FROM dashboard_cache.alert_evaluations WHERE tenant_id = :tenant AND rule_id = :rule")
			.param("tenant", tenant).param("rule", ruleId).query { rs, _ -> evaluationOf(rs) }.optional().orElse(null)

	fun evaluations(tenant: UUID): List<Evaluation> =
		cache.sql("SELECT * FROM dashboard_cache.alert_evaluations WHERE tenant_id = :tenant ORDER BY rule_id")
			.param("tenant", tenant).query { rs, _ -> evaluationOf(rs) }.list()

	fun record(tenant: UUID, evaluation: Evaluation) {
		cache.sql("""INSERT INTO dashboard_cache.alert_evaluations (tenant_id, rule_id, rule_version, cursor_at, cursor_date, evaluated_at, status, reason, window_start, window_end)
				VALUES (:tenant, :rule, :version, :cursorAt, :cursorDate, :at, :status, :reason, :windowStart, :windowEnd)
				ON CONFLICT (tenant_id, rule_id) DO UPDATE SET rule_version = EXCLUDED.rule_version, cursor_at = EXCLUDED.cursor_at, cursor_date = EXCLUDED.cursor_date,
					evaluated_at = EXCLUDED.evaluated_at, status = EXCLUDED.status, reason = EXCLUDED.reason, window_start = EXCLUDED.window_start, window_end = EXCLUDED.window_end""")
			.param("tenant", tenant).param("rule", evaluation.ruleId).param("version", evaluation.ruleVersion)
			.param("cursorAt", evaluation.cursorAt?.let(Timestamp::from), Types.TIMESTAMP)
			.param("cursorDate", evaluation.cursorDate?.let(java.sql.Date::valueOf), Types.DATE)
			.param("at", Timestamp.from(evaluation.evaluatedAt)).param("status", evaluation.status).param("reason", evaluation.reason, Types.VARCHAR)
			.param("windowStart", evaluation.windowStart?.let(Timestamp::from), Types.TIMESTAMP)
			.param("windowEnd", evaluation.windowEnd?.let(Timestamp::from), Types.TIMESTAMP)
			.update()
	}

	/** 같은 키의 알림이 있으면 아무것도 하지 않는다 — 같은 사건을 두 번 만들지 않는다. 새로 만들었으면 true. */
	fun insert(alert: StoredAlert, threshold: BigDecimal, ruleVersion: Long, now: Instant): Boolean =
		cache.sql("""INSERT INTO dashboard_cache.alerts (alert_id, tenant_id, rule_id, category, subject, window_key, status, qualified, occurred_at, last_seen_at,
					window_start, window_end, event_count, member_ids, summary, threshold_value, rule_version, version, created_at, updated_at)
				VALUES (:id, :tenant, :rule, :category, :subject, :key, :status, :qualified, :occurred, :lastSeen, :windowStart, :windowEnd, :count,
					CAST(:members AS jsonb), CAST(:summary AS jsonb), :threshold, :ruleVersion, 1, :now, :now)
				ON CONFLICT (tenant_id, rule_id, subject, window_key) DO NOTHING""")
			.param("id", alert.alertId).param("tenant", alert.tenantId).param("rule", alert.ruleId).param("category", alert.category)
			.param("subject", alert.subject).param("key", alert.windowKey).param("status", alert.status).param("qualified", alert.qualified)
			.param("occurred", Timestamp.from(alert.occurredAt)).param("lastSeen", Timestamp.from(alert.lastSeenAt))
			.param("windowStart", Timestamp.from(alert.windowStart)).param("windowEnd", Timestamp.from(alert.windowEnd)).param("count", alert.eventCount)
			.param("members", mapper.writeValueAsString(alert.memberIds)).param("summary", mapper.writeValueAsString(alert.summary))
			.param("threshold", threshold).param("ruleVersion", ruleVersion).param("now", Timestamp.from(now))
			.update() == 1

	/** 열린 묶음을 늘린다 — 마지막 위반·수·구성원·요약을 바꾸고 판을 올린다. */
	fun extend(alert: StoredAlert, now: Instant) {
		cache.sql("""UPDATE dashboard_cache.alerts SET last_seen_at = :lastSeen, window_end = :windowEnd, event_count = :count, member_ids = CAST(:members AS jsonb),
					summary = CAST(:summary AS jsonb), qualified = :qualified, version = version + 1, updated_at = :now
				WHERE alert_id = :id""")
			.param("lastSeen", Timestamp.from(alert.lastSeenAt)).param("windowEnd", Timestamp.from(alert.windowEnd)).param("count", alert.eventCount)
			.param("members", mapper.writeValueAsString(alert.memberIds)).param("summary", mapper.writeValueAsString(alert.summary))
			.param("qualified", alert.qualified).param("now", Timestamp.from(now)).param("id", alert.alertId).update()
	}

	/** 마지막 위반이 [before] 이전인 열린 묶음을 닫는다. 닫힌 수. */
	fun closeQuiet(tenant: UUID, ruleId: String, before: Instant, now: Instant): Int =
		cache.sql("""UPDATE dashboard_cache.alerts SET status = 'closed', updated_at = :now
				WHERE tenant_id = :tenant AND rule_id = :rule AND status = 'open' AND last_seen_at <= :before""")
			.param("tenant", tenant).param("rule", ruleId).param("before", Timestamp.from(before)).param("now", Timestamp.from(now)).update()

	fun close(alertId: UUID, now: Instant) {
		cache.sql("UPDATE dashboard_cache.alerts SET status = 'closed', updated_at = :now WHERE alert_id = :id")
			.param("id", alertId).param("now", Timestamp.from(now)).update()
	}

	fun open(tenant: UUID, ruleId: String, subject: String): StoredAlert? =
		cache.sql("SELECT * FROM dashboard_cache.alerts WHERE tenant_id = :tenant AND rule_id = :rule AND subject = :subject AND status = 'open'")
			.param("tenant", tenant).param("rule", ruleId).param("subject", subject).query { rs, _ -> alertOf(rs) }.optional().orElse(null)

	/** 조직의 알림(임계값에 이른 것만), 최근 발생 순. */
	fun alerts(tenant: UUID): List<StoredAlert> =
		cache.sql("SELECT * FROM dashboard_cache.alerts WHERE tenant_id = :tenant AND qualified ORDER BY occurred_at DESC, alert_id")
			.param("tenant", tenant).query { rs, _ -> alertOf(rs) }.list()

	fun find(tenant: UUID, alertId: UUID): StoredAlert? =
		cache.sql("SELECT * FROM dashboard_cache.alerts WHERE tenant_id = :tenant AND alert_id = :id AND qualified")
			.param("tenant", tenant).param("id", alertId).query { rs, _ -> alertOf(rs) }.optional().orElse(null)

	private fun evaluationOf(rs: java.sql.ResultSet) = Evaluation(
		ruleId = rs.getString("rule_id"),
		ruleVersion = rs.getLong("rule_version"),
		cursorAt = rs.getTimestamp("cursor_at")?.toInstant(),
		cursorDate = rs.getDate("cursor_date")?.toLocalDate(),
		evaluatedAt = rs.getTimestamp("evaluated_at").toInstant(),
		status = rs.getString("status"),
		reason = rs.getString("reason"),
		windowStart = rs.getTimestamp("window_start")?.toInstant(),
		windowEnd = rs.getTimestamp("window_end")?.toInstant(),
	)

	@Suppress("UNCHECKED_CAST")
	private fun alertOf(rs: java.sql.ResultSet) = StoredAlert(
		alertId = rs.getObject("alert_id", UUID::class.java),
		tenantId = rs.getObject("tenant_id", UUID::class.java),
		ruleId = rs.getString("rule_id"),
		category = rs.getString("category"),
		subject = rs.getString("subject"),
		windowKey = rs.getString("window_key"),
		status = rs.getString("status"),
		qualified = rs.getBoolean("qualified"),
		occurredAt = rs.getTimestamp("occurred_at").toInstant(),
		lastSeenAt = rs.getTimestamp("last_seen_at").toInstant(),
		windowStart = rs.getTimestamp("window_start").toInstant(),
		windowEnd = rs.getTimestamp("window_end").toInstant(),
		eventCount = rs.getLong("event_count"),
		memberIds = mapper.readValue(rs.getString("member_ids"), List::class.java) as List<String>,
		summary = mapper.readValue(rs.getString("summary"), Map::class.java) as Map<String, Any?>,
		version = rs.getLong("version"),
	)
}
