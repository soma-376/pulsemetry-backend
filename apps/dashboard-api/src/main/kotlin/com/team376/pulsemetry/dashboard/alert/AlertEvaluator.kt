package com.team376.pulsemetry.dashboard.alert

import com.team376.pulsemetry.dashboard.analytics.AnalyticsFrames
import com.team376.pulsemetry.dashboard.analytics.Usage
import com.team376.pulsemetry.dashboard.analytics.UsageAggregator
import com.team376.pulsemetry.dashboard.analytics.UsageAggregator.Axis
import com.team376.pulsemetry.dashboard.analytics.UsageAggregator.Side
import com.team376.pulsemetry.dashboard.organization.OrganizationReader
import com.team376.pulsemetry.dashboard.request.CompareMode
import com.team376.pulsemetry.dashboard.request.ComparedPeriod
import com.team376.pulsemetry.dashboard.request.DatePeriod
import com.team376.pulsemetry.dashboard.request.QueryReader
import com.team376.pulsemetry.dashboard.snapshot.RetentionBoundaryReader
import com.team376.pulsemetry.dashboard.source.ClickHouseSourceReader
import com.team376.pulsemetry.dashboard.store.ClickHouseParam
import com.team376.pulsemetry.persistence.enrollment.alert.AlertRuleState
import com.team376.pulsemetry.persistence.enrollment.alert.AlertRules
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import java.math.BigDecimal
import java.math.MathContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 켜진 알림 규칙을 평가한다 (ADR 0051 §5·§6). 원천은 읽기만 하고 결과는 [AlertStore] 에 남긴다.
 *
 * - **급증**: 하루 D 마다 D 로 끝나는 7일과 그 앞 7일을 개요와 같은 snapshot·같은 비용 계산으로 본다. 두 기간이 모두 완전하지 않거나(ADR 0042),
 *   비용을 모르거나, 앞 7일이 0 이면 그날은 **평가하지 않는다**(사유를 남긴다). 증가율이 임계값 이상이면 (급증, D) 알림이다. D 는 확정 대기가 지난 날만이다.
 * - **24시간 규칙**: 명시 매핑된 관측 제품이 조직의 등록 제품 밖이면 제품별로 묶는다(허브 ADR 0008).
 *   사용량 대표 행만 세고, 모델 공급자·도구 이름·미분류 제품으로 등록 여부를 추정하지 않는다.
 * - 같은 사건은 같은 키(조직·규칙·대상·창의 키)라 다시 평가해도 알림이 늘지 않는다. 알림에는 무엇·언제·누가(구성원 ID)·몇 건만 싣는다.
 * - 규칙의 판이 바뀌면(껐다 켜면) 시작점을 다시 정한다 — 급증은 켠 날의 전날부터, 24시간 규칙은 켠 시각의 24시간 전부터.
 */
class AlertEvaluator(
	private val source: JdbcClient,
	private val reader: ClickHouseSourceReader,
	private val store: AlertStore,
	private val frames: AnalyticsFrames,
	private val aggregator: UsageAggregator,
	private val organizations: OrganizationReader,
	private val boundaries: RetentionBoundaryReader,
	/** 관측이 다 도착했다고 보는 대기 — 기간 완전성의 확정 대기와 같은 값이다(ADR 0042). */
	private val settle: Duration,
	private val lease: Duration,
	private val clock: Clock,
	private val owner: String = "dashboard-" + UUID.randomUUID(),
) {
	private val log = LoggerFactory.getLogger(AlertEvaluator::class.java)

	/** 켜진 규칙이 있는 조직을 하나씩 선점해 평가한다. 평가한 조직 수. */
	fun runOnce(): Int {
		val tenants = source.sql("SELECT DISTINCT r.tenant_id FROM enrollment.organization_alert_rules r JOIN enrollment.alert_rule_definitions d USING (rule_id) WHERE r.enabled AND d.active ORDER BY r.tenant_id")
			.query { rs, _ -> rs.getObject("tenant_id", UUID::class.java) }.list()
		var evaluated = 0
		for (tenant in tenants) {
			val now = clock.instant()
			if (!store.claim(tenant, owner, now, now.plus(lease))) continue
			try {
				evaluate(tenant)
				evaluated++
			} finally {
				store.release(tenant, owner)
			}
		}
		return evaluated
	}

	/** 한 조직의 켜진 규칙을 평가한다. 규칙 하나의 실패는 그 규칙의 기록(`failed`)으로 남기고 나머지를 계속한다. */
	fun evaluate(tenant: UUID) {
		val rules = AlertRules.rules(source, tenant)
		for (rule in rules) {
			val now = clock.instant()
			if (!rule.enabled) {
				// 꺼진 규칙의 열린 묶음은 닫는다 — 더는 늘지 않는다.
				store.closeQuiet(tenant, rule.ruleId, FAR_FUTURE, now)
				continue
			}
			val previous = store.evaluation(tenant, rule.ruleId)?.takeIf { it.ruleVersion == rule.version }
			try {
				when {
					!rule.available -> store.record(tenant, Evaluation(rule.ruleId, rule.version, previous?.cursorAt, previous?.cursorDate, now, NOT_EVALUATED,
						rule.reason, null, null))
					rule.ruleId == AlertRules.SPEND_SPIKE -> spend(tenant, rule, previous, now)
					rule.ruleId == AlertRules.PRODUCT_NOT_REGISTERED -> rolling(tenant, rule, previous, now)
					else -> store.record(tenant, Evaluation(rule.ruleId, rule.version, null, null, now, NOT_EVALUATED, AlertRules.SOURCE_NOT_AVAILABLE, null, null))
				}
			} catch (e: Exception) {
				log.error("알림 규칙 평가가 실패했다 — 다음 주기에 다시 한다 tenant={} rule={}", tenant, rule.ruleId, e)
				store.record(tenant, Evaluation(rule.ruleId, rule.version, previous?.cursorAt, previous?.cursorDate, now, FAILED, EVALUATION_ERROR, null, null))
			}
		}
	}

	private fun spend(tenant: UUID, rule: AlertRuleState, previous: Evaluation?, now: Instant) {
		val zone = QueryReader.SEOUL
		val latest = LocalDate.ofInstant(now.minus(settle), zone).minusDays(1)
		var first = previous?.cursorDate?.plusDays(1) ?: LocalDate.ofInstant(rule.updatedAt ?: now, zone).minusDays(1)
		if (first.isBefore(latest.minusDays(MAX_SPEND_DAYS - 1))) first = latest.minusDays(MAX_SPEND_DAYS - 1)
		if (first.isAfter(latest)) {
			if (previous == null) store.record(tenant, Evaluation(rule.ruleId, rule.version, null, null, now, NOT_EVALUATED, PERIOD_NOT_SETTLED, null, null))
			else store.record(tenant, previous.copy(evaluatedAt = now))
			return
		}
		val organization = organizations.find(tenant) ?: return
		var day = first
		while (!day.isAfter(latest)) {
			val period = ComparedPeriod(DatePeriod(day.minusDays(6), day, zone), CompareMode.PREV_WEEK)
			val windowStart = period.previous!!.from
			val windowEnd = period.current.until
			val frame = frames.frame(organization, SYSTEM, period, usesComparison = true, snapshotId = null)
			val reason: String? = if (!frame.comparable) PERIOD_INCOMPLETE else {
				val current = Usage.of(aggregator.totals(frame.snapshot, Side.CURRENT, Axis.ORGANIZATION).getValue(emptyList()), frame.pricingMixed, frame.currentComplete)
					.equivalentCostUsd?.toBigDecimal()
				val before = Usage.of(aggregator.totals(frame.snapshot, Side.PREVIOUS, Axis.ORGANIZATION).getValue(emptyList()), frame.pricingMixed, frame.previousComplete)
					.equivalentCostUsd?.toBigDecimal()
				when {
					current == null || before == null -> COST_NOT_AVAILABLE
					before.signum() == 0 -> NO_PREVIOUS_SPEND
					else -> {
						val ratio = (current - before).divide(before, MathContext.DECIMAL64)
						if (ratio >= rule.thresholdValue) {
							val key = day.toString()
							store.insert(StoredAlert(alertId(tenant, rule.ruleId, "", key), tenant, rule.ruleId, rule.category, "", key, CLOSED, true,
								occurredAt = windowEnd, lastSeenAt = windowEnd, windowStart = windowStart, windowEnd = windowEnd, eventCount = 0, memberIds = emptyList(),
								summary = linkedMapOf("currentStartDate" to period.current.startDate.toString(), "currentEndDate" to period.current.endDate.toString(),
									"previousStartDate" to period.previous!!.startDate.toString(), "previousEndDate" to period.previous!!.endDate.toString(),
									"currentCostUsd" to current.toPlainString(), "previousCostUsd" to before.toPlainString(),
									"increaseRatio" to ratio.round(MathContext(6)).toDouble(), "threshold" to rule.thresholdValue.toDouble()),
								version = 1), rule.thresholdValue, rule.version, now)
						}
						null
					}
				}
			}
			store.record(tenant, Evaluation(rule.ruleId, rule.version, null, day, now, if (reason == null) EVALUATED else NOT_EVALUATED, reason, windowStart, windowEnd))
			day = day.plusDays(1)
		}
	}

	private fun rolling(tenant: UUID, rule: AlertRuleState, previous: Evaluation?, now: Instant) {
		val horizon = now.minus(settle).truncatedTo(ChronoUnit.MICROS)
		val deletedBefore = boundaries.read(tenant).deletedBefore
		var from = previous?.cursorAt ?: (rule.updatedAt ?: now).minus(WINDOW)
		if (deletedBefore != null && from.isBefore(deletedBefore)) from = deletedBefore
		val registered = AlertRules.registeredProducts(source, tenant)
		if (registered.isEmpty()) {
			store.record(tenant, Evaluation(rule.ruleId, rule.version, from, null, now, NOT_EVALUATED,
				AlertRules.REGISTERED_PRODUCTS_NOT_CONFIGURED, null, null))
			return
		}
		val mappings = source.sql("""SELECT o.observed_product, o.product_id, p.display_name
			FROM enrollment.vendor_catalog_observed_products o JOIN enrollment.vendor_catalog_products p ON p.id = o.product_id
			ORDER BY o.observed_product""")
			.query { rs, _ -> Triple(rs.getString("observed_product"), rs.getString("product_id"), rs.getString("display_name")) }
			.list().filter { it.second !in registered }
		val names = mappings.associate { it.second to it.third }
		if (from.isBefore(horizon) && mappings.isNotEmpty()) {
			val params = linkedMapOf(
				"tenant" to ClickHouseParam.string(tenant.toString()),
				"from" to ClickHouseParam.instant(from),
				"until" to ClickHouseParam.instant(horizon),
				"observed" to ClickHouseParam.stringArray(mappings.map { it.first }),
				"products" to ClickHouseParam.stringArray(mappings.map { it.second }),
			)
			val sql = """
				SELECT subject, toString(min(source_time)) AS first_at, toString(max(source_time)) AS last_at, count() AS n,
				    arraySort(groupUniqArray(ifNull(member_id, ''))) AS members
				FROM (
				    SELECT transform(product, {observed:Array(String)}, {products:Array(String)}, '') AS subject, source_time, member_id, toStartOfHour(source_time) AS hour
				    FROM telemetry_events FINAL
				    WHERE tenant_id = {tenant:String} AND record_status = 'active' AND signal = 'log' AND event_type = 'model.response.usage'
				      AND usage_role = 'primary' AND mapping_status = 'mapped' AND has({observed:Array(String)}, product)
				      AND source_time >= {from:DateTime64(9, 'UTC')} AND source_time < {until:DateTime64(9, 'UTC')}
				)
				GROUP BY subject, hour
				ORDER BY subject, hour
				SETTINGS do_not_merge_across_partitions_select_final = 1
			""".trimIndent()
			val buckets = reader.query(sql, params) { row ->
				Bucket(row.path("subject").asString(), parse(row.path("first_at").asString()), parse(row.path("last_at").asString()),
					row.path("n").asLong(), row.path("members").toList().map { it.asString() }.filter { it.isNotEmpty() })
			}
			for (bucket in buckets) absorb(tenant, rule, bucket, names.getValue(bucket.subject), now)
		}
		// 24시간 동안 위반이 없는 묶음은 끝났다.
		store.closeQuiet(tenant, rule.ruleId, horizon.minus(WINDOW), now)
		store.record(tenant, Evaluation(rule.ruleId, rule.version, maxOf(from, horizon), null, now, EVALUATED, null, from, horizon))
	}

	/** 위반 묶음 하나(한 시간 안의 같은 대상)를 열린 묶음에 붙이거나 새 묶음을 연다. */
	private fun absorb(tenant: UUID, rule: AlertRuleState, bucket: Bucket, productName: String, now: Instant) {
		val open = store.open(tenant, rule.ruleId, bucket.subject)
		if (open != null && Duration.between(open.lastSeenAt, bucket.firstAt) < WINDOW) {
			val count = open.eventCount + bucket.count
			store.extend(open.copy(lastSeenAt = maxOf(open.lastSeenAt, bucket.lastAt), windowEnd = maxOf(open.lastSeenAt, bucket.lastAt), eventCount = count,
				memberIds = (open.memberIds + bucket.members).distinct().sorted(), qualified = BigDecimal(count) >= rule.thresholdValue,
				summary = rollingSummary(bucket.subject, productName, count, rule)), now)
			return
		}
		if (open != null) store.close(open.alertId, now)
		val key = bucket.firstAt.toString()
		store.insert(StoredAlert(alertId(tenant, rule.ruleId, bucket.subject, key), tenant, rule.ruleId, rule.category, bucket.subject, key, OPEN,
			qualified = BigDecimal(bucket.count) >= rule.thresholdValue, occurredAt = bucket.firstAt, lastSeenAt = bucket.lastAt, windowStart = bucket.firstAt,
			windowEnd = bucket.lastAt, eventCount = bucket.count, memberIds = bucket.members.distinct().sorted(), summary = rollingSummary(bucket.subject, productName, bucket.count, rule),
			version = 1), rule.thresholdValue, rule.version, now)
	}

	private fun rollingSummary(subject: String, productName: String, count: Long, rule: AlertRuleState): Map<String, Any?> = linkedMapOf(
		"productId" to subject, "productName" to productName, "events" to count, "threshold" to rule.thresholdValue.toDouble(),
	)

	private data class Bucket(val subject: String, val firstAt: Instant, val lastAt: Instant, val count: Long, val members: List<String>)

	companion object {
		const val EVALUATED = "evaluated"
		const val NOT_EVALUATED = "not_evaluated"
		const val FAILED = "failed"
		const val OPEN = "open"
		const val CLOSED = "closed"

		/** 두 기간 중 하나라도 완전하지 않다(ADR 0042). */
		const val PERIOD_INCOMPLETE = "period_incomplete"
		/** 비용을 모르는 사용이 있다(가격 없음·가격 판 섞임). */
		const val COST_NOT_AVAILABLE = "cost_not_available"
		/** 앞 기간 비용이 0 이라 증가율이 없다. */
		const val NO_PREVIOUS_SPEND = "no_previous_spend"
		/** 아직 확정 대기가 지난 날이 없다. */
		const val PERIOD_NOT_SETTLED = "period_not_settled"
		const val EVALUATION_ERROR = "evaluation_error"

		val WINDOW: Duration = Duration.ofHours(24)
		/** 한 번에 평가하는 급증의 날 수 상한 — 오래 멈췄다 켜져도 한 회차가 끝없이 길어지지 않는다. */
		const val MAX_SPEND_DAYS = 31L
		/** 평가가 만드는 snapshot 의 요청자. 사람이 아니다. */
		val SYSTEM: UUID = UUID(0, 0)
		private val FAR_FUTURE: Instant = Instant.parse("9999-01-01T00:00:00Z")

		/** 같은 사건은 같은 ID 다 — 키에서 만든다. */
		fun alertId(tenant: UUID, ruleId: String, subject: String, windowKey: String): UUID =
			UUID.nameUUIDFromBytes("alert/$tenant/$ruleId/$subject/$windowKey".toByteArray())

		/** ClickHouse `toString(DateTime64(9, 'UTC'))` → 시각. */
		internal fun parse(text: String): Instant = Instant.parse(text.replace(' ', 'T') + "Z")
	}
}
