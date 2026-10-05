package com.team376.pulsemetry.dashboard.alert

import com.team376.pulsemetry.dashboard.analytics.AnalyticsFrames
import com.team376.pulsemetry.dashboard.analytics.Availability
import com.team376.pulsemetry.dashboard.analytics.CurrentMeta
import com.team376.pulsemetry.dashboard.analytics.CurrentStateTokens
import com.team376.pulsemetry.dashboard.analytics.OverviewResponse
import com.team376.pulsemetry.dashboard.analytics.Page
import com.team376.pulsemetry.dashboard.error.DashboardException
import com.team376.pulsemetry.dashboard.error.ErrorCode
import com.team376.pulsemetry.dashboard.error.FieldErrorCode
import com.team376.pulsemetry.dashboard.organization.Organization
import com.team376.pulsemetry.dashboard.request.PageCursor
import com.team376.pulsemetry.dashboard.request.PageCursorCodec
import com.team376.pulsemetry.dashboard.request.PageRequest
import com.team376.pulsemetry.dashboard.request.QueryReader
import com.team376.pulsemetry.persistence.enrollment.alert.AlertRules
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * 알림 조회 (ADR 0051 §6). 평가 기록은 자기 스키마(캐시 계정)에서, 확인은 enrollment(원천 계정)에서 읽는다.
 * 개요의 `alerts` 와 알림 목록은 같은 계산으로 미확인을 센다 — 미확인 = 임계값에 이른 알림 중 확인 기록이 없는 것. 조회 기간과 무관하다.
 */
class AlertService(
	private val source: JdbcClient,
	private val store: AlertStore,
	private val tokens: CurrentStateTokens,
	private val codec: PageCursorCodec,
	private val clock: Clock,
) {
	enum class Status(val wire: String) {
		UNACKNOWLEDGED("unacknowledged"), ACKNOWLEDGED("acknowledged"), ALL("all");

		companion object {
			val BY_WIRE = entries.associateBy { it.wire }
		}
	}

	/** 개요의 "보안 경보 및 알림". 평가한 적이 없으면 unavailable 과 사유다(켜진 규칙이 없거나 아직 평가 전). */
	fun overview(tenant: UUID, now: Instant): OverviewResponse.Alerts {
		val state = state(tenant)
		// asOf 는 마지막 평가 시각이고, 평가 기록이 없을 때만 응답 시각이다.
		if (state.availability != Availability.AVAILABLE) return OverviewResponse.Alerts(state.availability, state.reason, (state.asOf ?: now).toString(), null, null, null)
		val open = unacknowledged(tenant)
		return OverviewResponse.Alerts(Availability.AVAILABLE, null, state.asOf!!.toString(), open.size.toLong(),
			open.count { it.category == SECURITY }.toLong(), open.count { it.category == COST }.toLong())
	}

	fun list(organization: Organization, status: Status, category: String?, page: PageRequest, snapshotId: String?): AlertsResponse {
		val now = clock.instant()
		val token = tokens.resolve(KIND, organization.id, snapshotId ?: page.cursor?.snapshotId, now)
		val scope = "$KIND:${status.wire}:${category.orEmpty()}"
		page.cursor?.let { if (it.snapshotId != token.value || it.scope != scope) throw DashboardException.invalid(CURSOR, FieldErrorCode.INVALID_CURSOR) }
		val acks = acknowledgements(organization.id)
		val all = store.alerts(organization.id).filter { alert ->
			(category == null || alert.category == category) && when (status) {
				Status.UNACKNOWLEDGED -> alert.alertId !in acks
				Status.ACKNOWLEDGED -> alert.alertId in acks
				Status.ALL -> true
			}
		}
		val start = page.cursor?.let { cursor ->
			val index = all.indexOfFirst { it.alertId.toString() == cursor.after.lastOrNull() }
			if (index < 0) throw DashboardException.invalid(CURSOR, FieldErrorCode.INVALID_CURSOR)
			index + 1
		} ?: 0
		val items = all.drop(start).take(page.limit)
		val next = if (start + items.size < all.size) codec.encode(PageCursor(token.value, scope, listOf(items.last().occurredAt.toString(), items.last().alertId.toString()))) else null
		val accounts = accounts(organization.id, items.flatMap { it.memberIds })
		return AlertsResponse(meta(organization, now, token), evaluation(organization.id), Page(items.map { item(it, acks[it.alertId], accounts) }, all.size, next))
	}

	fun detail(organization: Organization, alertId: String): AlertResponse {
		val now = clock.instant()
		val id = runCatching { UUID.fromString(alertId) }.getOrNull() ?: throw DashboardException(ErrorCode.NOT_FOUND)
		val alert = store.find(organization.id, id) ?: throw DashboardException(ErrorCode.NOT_FOUND)
		val token = tokens.issue(KIND, organization.id, now)
		return AlertResponse(meta(organization, now, token), item(alert, acknowledgements(organization.id)[alert.alertId], accounts(organization.id, alert.memberIds)))
	}

	/** 규칙마다 마지막 평가. 켠 규칙 중 평가 기록이 없는 것은 대기다. */
	fun evaluation(tenant: UUID): AlertsEvaluation {
		val state = state(tenant)
		val evaluations = store.evaluations(tenant).associateBy { it.ruleId }
		return AlertsEvaluation(state.availability, state.reason, state.asOf?.toString(), AlertRules.rules(source, tenant).map { rule ->
			val evaluation = evaluations[rule.ruleId]?.takeIf { it.ruleVersion == rule.version || !rule.enabled }
			AlertRuleEvaluation(rule.ruleId, rule.enabled, evaluation?.evaluatedAt?.toString(), evaluation?.status, evaluation?.reason,
				evaluation?.windowStart?.toString(), evaluation?.windowEnd?.toString())
		})
	}

	private data class State(val availability: String, val reason: String?, val asOf: Instant?)

	/**
	 * 켠 규칙마다 지금 판의 평가 기록이 있어야 available 이다(ADR 0051 §6 — 켰지만 아직 평가 전이면 `evaluation_pending`). 평가하지 않은 규칙을
	 * 0건으로 읽히게 두지 않는다 — 첫 회차 도중이나 규칙을 다시 켠 직후에도 그 규칙이 평가될 때까지 pending 이다. 켠 규칙이 없으면 남은 기록으로 판단한다.
	 */
	private fun state(tenant: UUID): State {
		val evaluations = store.evaluations(tenant)
		val asOf = evaluations.maxOfOrNull { it.evaluatedAt }
		val evaluated = evaluations.map { it.ruleId to it.ruleVersion }.toSet()
		val enabled = AlertRules.rules(source, tenant).filter { it.enabled }
		if (enabled.any { (it.ruleId to it.version) !in evaluated }) return State(Availability.UNAVAILABLE, EVALUATION_PENDING, asOf)
		if (evaluations.isNotEmpty()) return State(Availability.AVAILABLE, null, asOf)
		return State(Availability.UNAVAILABLE, Availability.EVALUATION_NOT_CONFIGURED, null)
	}

	private fun unacknowledged(tenant: UUID): List<StoredAlert> {
		val acks = acknowledgements(tenant)
		return store.alerts(tenant).filter { it.alertId !in acks }
	}

	private data class Ack(val at: Instant, val by: UUID)

	private fun acknowledgements(tenant: UUID): Map<UUID, Ack> =
		source.sql("SELECT alert_id, acknowledged_at, acknowledged_by FROM enrollment.alert_acknowledgements WHERE tenant_id = :tenant")
			.param("tenant", tenant)
			.query { rs, _ -> rs.getObject("alert_id", UUID::class.java) to Ack(rs.getTimestamp("acknowledged_at").toInstant(), rs.getObject("acknowledged_by", UUID::class.java)) }
			.list().toMap()

	/** 구성원 ID → 계정(이메일). 그 조직의 구성원만. */
	private fun accounts(tenant: UUID, ids: Collection<String>): Map<String, String> {
		val uuids = ids.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }.distinct()
		if (uuids.isEmpty()) return emptyMap()
		return source.sql("SELECT id, email FROM enrollment.members WHERE tenant_id = :tenant AND id IN (:ids)")
			.param("tenant", tenant).param("ids", uuids)
			.query { rs, _ -> rs.getObject("id", UUID::class.java).toString() to rs.getString("email") }.list().toMap()
	}

	private fun item(alert: StoredAlert, ack: Ack?, accounts: Map<String, String>): AlertItem {
		val rolling = alert.ruleId != AlertRules.SPEND_SPIKE
		return AlertItem(
			alertId = alert.alertId.toString(),
			version = alert.version,
			ruleId = alert.ruleId,
			category = alert.category,
			status = alert.status,
			occurredAt = alert.occurredAt.toString(),
			lastSeenAt = alert.lastSeenAt.toString(),
			windowStart = alert.windowStart.toString(),
			windowEnd = alert.windowEnd.toString(),
			subject = alert.subject.takeIf { rolling },
			eventCount = alert.eventCount.takeIf { rolling },
			memberCount = alert.memberIds.size.toLong().takeIf { rolling },
			members = alert.memberIds.map { AlertMember(it, accounts[it]) },
			summary = alert.summary,
			acknowledgement = ack?.let { AlertAcknowledgement(it.at.toString(), it.by.toString()) },
		)
	}

	private fun meta(organization: Organization, now: Instant, token: CurrentStateTokens.Token) =
		CurrentMeta(organization.id.toString(), now.toString(), token.asOf.toString(), token.value, AnalyticsFrames.USD, QueryReader.SEOUL_ID)

	companion object {
		const val KIND = "alerts"
		const val SECURITY = "security"
		const val COST = "cost"
		/** 켠 규칙이 있지만 아직 한 번도 평가하지 않았다. */
		const val EVALUATION_PENDING = "evaluation_pending"
		private const val CURSOR = "cursor"
		val CATEGORIES = setOf(SECURITY, COST)
	}
}

data class AlertsResponse(val meta: CurrentMeta, val evaluation: AlertsEvaluation, val alerts: Page<AlertItem>)

data class AlertResponse(val meta: CurrentMeta, val alert: AlertItem)

/** 평가 상태 — 개요의 `alerts` 와 같은 가용성·사유와, 규칙마다 마지막 평가. */
data class AlertsEvaluation(val availability: String, val reason: String?, val asOf: String?, val rules: List<AlertRuleEvaluation>)

/** [status] 가 `not_evaluated` 면 [reason] 이 왜 평가하지 않았는지다 — 0건과 다르다. 평가 기록이 없으면 셋 다 null 이다. */
data class AlertRuleEvaluation(val ruleId: String, val enabled: Boolean, val evaluatedAt: String?, val status: String?, val reason: String?,
	val windowStart: String?, val windowEnd: String?)

/**
 * 알림 하나. [subject]·[eventCount]·[memberCount] 는 24시간 규칙(모델·도구)만 있다 — 급증은 조직의 판정이라 null 이다.
 * [members] 의 [AlertMember.account] 는 지금 그 조직의 구성원이 아니면 null 이다.
 */
data class AlertItem(
	val alertId: String,
	val version: Long,
	val ruleId: String,
	val category: String,
	val status: String,
	val occurredAt: String,
	val lastSeenAt: String,
	val windowStart: String,
	val windowEnd: String,
	val subject: String?,
	val eventCount: Long?,
	val memberCount: Long?,
	val members: List<AlertMember>,
	val summary: Map<String, Any?>,
	val acknowledgement: AlertAcknowledgement?,
)

data class AlertMember(val memberId: String, val account: String?)

data class AlertAcknowledgement(val acknowledgedAt: String, val acknowledgedBy: String)
