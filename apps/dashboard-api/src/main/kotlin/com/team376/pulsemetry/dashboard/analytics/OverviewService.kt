package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.analytics.UsageAggregator.Axis
import com.team376.pulsemetry.dashboard.analytics.UsageAggregator.Side
import com.team376.pulsemetry.dashboard.organization.Organization
import com.team376.pulsemetry.dashboard.request.CompareMode
import com.team376.pulsemetry.dashboard.request.ComparedPeriod
import com.team376.pulsemetry.dashboard.snapshot.SnapshotManifestStore
import com.team376.pulsemetry.dashboard.snapshot.SnapshotService
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

/**
 * 개요 응답을 만든다 (개요 명세). **요청마다 snapshot 을 새로 만들고**(ADR 0023 §4 — 요청 안에서 동기 build) 그 snapshot 하나에서 모든
 * section 을 계산한다. 개요는 snapshot ID 를 응답에 싣지 않는다.
 *
 * ## 상태 우선
 *
 * `dataState` 를 집계보다 먼저 정한다. 수신 이력도 요청 기간의 관측도 없으면 `never_observed`, 이력은 있으나 관측이 없으면 `no_data`,
 * 관측이 있으면 `partial` 이다 — v1 은 완전 관측의 근거가 없어 `ready` 를 내지 않는다. 앞의 둘이면 `usage.current = null`, 모델·상위 팀은
 * 빈 배열이고 팀 금액·사용자 수는 null 이다. 관측은 snapshot 에 고정한 source_time 일자다(ledger 의 수신 날짜가 아니다).
 *
 * ## 합계의 일관성
 *
 * 조직·일자·모델·팀 합계가 모두 같은 snapshot payload 의 [UsageAggregator] 에서 나온다. 금액이 모두 있으면 조직 금액 = 일자 합 = 모델 합
 * = 상위 3팀 + 나머지 팀 + 미배분이다. 어느 그룹의 금액이 없으면 그 그룹을 담는 합도 없다 — 부분합을 총액으로 올리지 않는다.
 *
 * `dataThrough` 는 null 이다 — 공통 데이터 완료 경계를 보증할 근거가 없고, ledger 의 최대 수신 시각은 그 값이 아니다.
 */
class OverviewService(
	private val snapshots: SnapshotService,
	private val aggregator: UsageAggregator,
	private val references: SnapshotReferences,
	private val ingest: IngestStatusReader,
	private val comparison: ComparisonPolicy,
	private val clock: Clock,
) {

	private val log = LoggerFactory.getLogger(OverviewService::class.java)

	fun overview(organization: Organization, requestedBy: UUID, period: ComparedPeriod): OverviewResponse {
		val history = ingest.history(organization.id)
		val snapshot = snapshots.create(organization.id, requestedBy, period)
		val now = clock.instant()

		val observed = references.observedDates(snapshot)
		val currentDates = period.current.dates()
		val currentCoverage = Coverage.of(currentDates.count { it in observed })
		val previousCoverage = period.previous?.let { previous -> Coverage.of(previous.dates().count { it in observed }) }
		val dataState = when {
			currentCoverage.observedDays > 0 -> PARTIAL
			history.hasReceipts -> NO_DATA
			else -> NEVER_OBSERVED
		}
		val empty = dataState != PARTIAL
		val pricingMixed = snapshot.pricingVersions.size > 1
		val comparable = previousCoverage != null && comparison.comparable(currentCoverage, previousCoverage)

		val organizationTotals = aggregator.totals(snapshot, Side.CURRENT, Axis.ORGANIZATION).getValue(emptyList())
		val previousTotals = if (comparable) aggregator.totals(snapshot, Side.PREVIOUS, Axis.ORGANIZATION).getValue(emptyList()) else null
		logCounters(snapshot, organizationTotals)

		return OverviewResponse(
			meta = OverviewResponse.Meta(
				organizationId = organization.id.toString(),
				generatedAt = now.toString(),
				dataThrough = null,
				currency = USD,
				startDate = period.current.startDate.toString(),
				endDate = period.current.endDate.toString(),
				timeZone = period.current.zone.id,
				dayCount = period.current.days,
				dataState = dataState,
				currentCoverage = currentCoverage,
				pricingVersion = snapshot.pricingVersions.singleOrNull(),
			),
			comparison = comparisonOf(period, previousCoverage, comparable),
			ingest = ingestOf(organization.id, history, now),
			usage = OverviewResponse.UsagePair(
				current = if (empty) null else Usage.of(organizationTotals, pricingMixed),
				previous = previousTotals?.let { Usage.of(it, pricingMixed) },
			),
			seats = SEATS,
			alerts = OverviewResponse.Alerts(Availability.UNAVAILABLE, Availability.EVALUATION_NOT_CONFIGURED, now.toString(), null, null, null),
			trend = trendOf(snapshot, currentDates, observed, pricingMixed, empty),
			modelMix = modelMixOf(snapshot, pricingMixed, empty),
			waste = WASTE,
			teamUsage = teamUsageOf(snapshot, pricingMixed, empty, comparable),
		)
	}

	private fun comparisonOf(period: ComparedPeriod, previousCoverage: Coverage?, comparable: Boolean): OverviewResponse.Comparison {
		val previous = period.previous
		if (period.mode == CompareMode.NONE || previous == null) {
			return OverviewResponse.Comparison(CompareMode.NONE.wire, null, null, DISABLED, null, null)
		}
		return OverviewResponse.Comparison(
			mode = period.mode.wire,
			startDate = previous.startDate.toString(),
			endDate = previous.endDate.toString(),
			status = if (comparable) AVAILABLE_STATUS else UNAVAILABLE_STATUS,
			reason = if (comparable) null else Availability.SOURCE_NOT_AVAILABLE,
			coverage = previousCoverage,
		)
	}

	private fun ingestOf(tenantId: UUID, history: IngestStatusReader.History, now: java.time.Instant): OverviewResponse.Ingest {
		val empty = !history.hasReceipts
		return OverviewResponse.Ingest(
			// 수신 이력이 없으면 empty. 있으면 heartbeat·수집기 상태의 근거가 없어 healthy·delayed·down 을 추정하지 않는다.
			status = if (empty) INGEST_EMPTY else INGEST_UNKNOWN,
			reason = if (empty) null else Availability.SOURCE_NOT_AVAILABLE,
			asOf = now.toString(),
			firstObservedAt = history.summary?.firstObservedAt?.toString(),
			lastReceivedAt = history.summary?.lastReceivedAt?.toString(),
			windowMinutes = WINDOW.toMinutes().toInt(),
			activeInstallations = null,
			observedMembers = ingest.observedMembers(tenantId, now - WINDOW),
			eligibleMembers = ingest.eligibleMembers(tenantId),
			coverageRatio = null,
		)
	}

	private fun trendOf(
		snapshot: SnapshotManifestStore.Manifest,
		dates: List<LocalDate>,
		observed: Set<LocalDate>,
		pricingMixed: Boolean,
		empty: Boolean,
	): OverviewResponse.Trend {
		val byDay = if (empty) emptyMap() else aggregator.totals(snapshot, Side.CURRENT, Axis.DAY)
		val points = dates.map { date ->
			val totals = byDay[listOf(date.toString())] ?: UsageTotals.EMPTY
			val seen = date in observed
			OverviewResponse.TrendPoint(
				date = date.toString(),
				observation = if (seen) PARTIAL_OBSERVATION else UNOBSERVED,
				equivalentCostUsd = if (seen) totals.equivalentCost(pricingMixed)?.let(Money::format) else null,
				allocatedSeatCostUsd = null,
				totalTokens = if (seen) totals.apiTotal() else null,
			)
		}
		return OverviewResponse.Trend(DAY_BUCKET, points)
	}

	private fun modelMixOf(snapshot: SnapshotManifestStore.Manifest, pricingMixed: Boolean, empty: Boolean): OverviewResponse.ModelMix {
		if (empty) return OverviewResponse.ModelMix(Availability.UNAVAILABLE, Availability.SOURCE_NOT_AVAILABLE, emptyList())
		val models = aggregator.totals(snapshot, Side.CURRENT, Axis.MODEL)
			.map { (key, totals) ->
				val modelId = key.single() ?: error("model_id 는 비어 있을 수 없다")
				val cost = totals.equivalentCost(pricingMixed)
				val tokens = totals.apiTotal()
				OverviewResponse.ModelShare(
					modelId = modelId,
					displayName = ModelNames.displayName(modelId),
					equivalentCostUsd = cost?.let(Money::format),
					totalTokens = tokens,
					effectiveCostPerMillionTokensUsd = Money.perMillion(cost, tokens)?.let(Money::format),
				) to cost
			}
			.sortedWith(compareBy<Pair<OverviewResponse.ModelShare, BigDecimal?>, BigDecimal?>(nullsLast(reverseOrder())) { it.second }.thenBy { it.first.modelId })
			.map { it.first }
		val complete = models.all { it.equivalentCostUsd != null && it.totalTokens != null }
		return when {
			models.isEmpty() -> OverviewResponse.ModelMix(Availability.UNAVAILABLE, Availability.SOURCE_NOT_AVAILABLE, emptyList())
			complete -> OverviewResponse.ModelMix(Availability.AVAILABLE, null, models)
			else -> OverviewResponse.ModelMix(Availability.PARTIAL, Availability.SOURCE_NOT_AVAILABLE, models)
		}
	}

	private fun teamUsageOf(
		snapshot: SnapshotManifestStore.Manifest,
		pricingMixed: Boolean,
		empty: Boolean,
		comparable: Boolean,
	): OverviewResponse.TeamUsage {
		val directory = references.teams(snapshot).associateBy { it.id.toString() }
		val current = if (empty) emptyMap() else aggregator.totals(snapshot, Side.CURRENT, Axis.TEAM)
		val previous = if (comparable && !empty) aggregator.totals(snapshot, Side.PREVIOUS, Axis.TEAM) else emptyMap()
		val teamModels = if (empty) emptyMap() else aggregator.totals(snapshot, Side.CURRENT, Axis.TEAM_MODEL)

		val teams = current.filterKeys { it.single() != null }.map { (key, totals) -> key.single()!! to totals }
		val ranked = teams.sortedWith(
			compareBy<Pair<String, UsageTotals>, BigDecimal?>(nullsLast(reverseOrder())) { it.second.equivalentCost(pricingMixed) }.thenBy { it.first },
		)
		val top = ranked.take(TOP_TEAMS)
		val topIds = top.map { it.first }.toSet()
		val teamCount = (directory.values.filter { !it.archived }.map { it.id.toString() } + teams.map { it.first }).toSet().size

		val topTeams = top.map { (teamId, totals) ->
			OverviewResponse.TopTeam(
				teamId = teamId,
				teamName = directory[teamId]?.name ?: teamId,
				current = TeamPeriod.of(totals, pricingMixed),
				previous = if (comparable) TeamPeriod.of(previous[listOf(teamId)] ?: UsageTotals.EMPTY, pricingMixed) else null,
				topModel = topModelOf(teamId, totals, teamModels, pricingMixed),
			)
		}
		val others = ranked.drop(TOP_TEAMS).map { it.second }
		val previousOthers = previous.filterKeys { key -> key.single().let { it != null && it !in topIds } }.values
		val unassigned = current[listOf(null)] ?: UsageTotals.EMPTY

		val complete = !empty && topTeams.all { it.current.equivalentCostUsd != null } && unassigned.equivalentCost(pricingMixed) != null &&
			others.all { it.equivalentCost(pricingMixed) != null }
		val (availability, reason) = when {
			empty || (teams.isEmpty() && !unassigned.hasUsage) -> Availability.UNAVAILABLE to Availability.SOURCE_NOT_AVAILABLE
			complete -> Availability.AVAILABLE to null
			else -> Availability.PARTIAL to Availability.SOURCE_NOT_AVAILABLE
		}
		return OverviewResponse.TeamUsage(
			availability = availability,
			reason = reason,
			ranking = RANKING,
			attributionBasis = ATTRIBUTION_BASIS,
			totalTeamCount = teamCount,
			topTeams = topTeams,
			otherTeams = OverviewResponse.OtherTeams(
				count = teamCount - topTeams.size,
				currentEquivalentCostUsd = sumOrNull(others, pricingMixed)?.let(Money::format),
				previousEquivalentCostUsd = if (comparable) sumOrNull(previousOthers, pricingMixed)?.let(Money::format) else null,
			),
			unassigned = OverviewResponse.Unassigned(
				current = TeamPeriod.of(unassigned, pricingMixed),
				previous = if (comparable) TeamPeriod.of(previous[listOf(null)] ?: UsageTotals.EMPTY, pricingMixed) else null,
			),
		)
	}

	/** 팀 안에서 금액이 가장 큰 모델(동률은 모델 ID 오름차순). 팀 금액이 없거나 0 이면 비율을 낼 수 없어 없다. */
	private fun topModelOf(
		teamId: String,
		team: UsageTotals,
		teamModels: Map<List<String?>, UsageTotals>,
		pricingMixed: Boolean,
	): TopModel? {
		val teamCost = team.equivalentCost(pricingMixed) ?: return null
		val (modelId, cost) = teamModels.entries
			.filter { it.key[0] == teamId }
			.mapNotNull { entry -> entry.value.equivalentCost(pricingMixed)?.let { entry.key[1]!! to it } }
			.sortedWith(compareByDescending<Pair<String, BigDecimal>> { it.second }.thenBy { it.first })
			.firstOrNull() ?: return null
		val share = Money.share(cost, teamCost) ?: return null
		return TopModel(modelId, ModelNames.displayName(modelId), share)
	}

	/** 그룹이 하나도 없거나 어느 그룹의 금액이 없으면 합도 없다 — 0 을 확정하지 않고 부분합을 올리지 않는다. */
	private fun sumOrNull(groups: Collection<UsageTotals>, pricingMixed: Boolean): BigDecimal? {
		if (groups.isEmpty()) return null
		val costs = groups.map { it.equivalentCost(pricingMixed) ?: return null }
		return costs.fold(BigDecimal.ZERO, BigDecimal::add)
	}

	/** DTO 에 자리가 없는 진단값은 로그로만 남긴다 — 응답을 넓히지 않는다. */
	private fun logCounters(snapshot: SnapshotManifestStore.Manifest, totals: UsageTotals) {
		log.info(
			"overview tenant={} snapshot={} usage_rows={} unidentified={} sessionless={} unverified={} unpriced={} multi_team={} pricing_versions={}",
			snapshot.tenantId, snapshot.snapshotId, totals.usageRows, totals.unidentifiedRows, totals.sessionlessRows,
			totals.unverifiedRows, totals.unpricedRows, totals.multiTeamRows, snapshot.pricingVersions,
		)
	}

	companion object {
		/** 화면 요청서가 제안한 운영 창. */
		val WINDOW: Duration = Duration.ofMinutes(15)

		private const val USD = "USD"
		private const val PARTIAL = "partial"
		private const val NO_DATA = "no_data"
		private const val NEVER_OBSERVED = "never_observed"
		private const val PARTIAL_OBSERVATION = "partial"
		private const val UNOBSERVED = "unobserved"
		private const val DISABLED = "disabled"
		private const val AVAILABLE_STATUS = "available"
		private const val UNAVAILABLE_STATUS = "unavailable"
		private const val INGEST_EMPTY = "empty"
		private const val INGEST_UNKNOWN = "unknown"
		private const val DAY_BUCKET = "day"
		private const val RANKING = "equivalentCostUsd_desc"
		/** 사용 행의 source_time 당시 대표 팀(`team_id_as_of`)에 귀속한다 — 현재 팀 매핑이 아니다. */
		private const val ATTRIBUTION_BASIS = "event_time"
		private const val TOP_TEAMS = 3

		/** 계약 모델이 화면의 좌석×월 요금과 맞지 않아 좌석 section 은 지원하지 않는다. */
		private val SEATS = OverviewResponse.Seats(Availability.UNAVAILABLE, Availability.NOT_APPLICABLE, emptyList(), null, null, null, null)

		private val WASTE = OverviewResponse.Waste(
			availability = Availability.UNAVAILABLE,
			reason = Availability.METHODOLOGY_NOT_AVAILABLE,
			methodologyVersion = null,
			totalMonthlyEquivalentCostUsd = null,
			items = listOf("cache_miss", "retry_or_abort", "excessive_context").map {
				OverviewResponse.WasteItem(it, Availability.UNAVAILABLE, Availability.METHODOLOGY_NOT_AVAILABLE, null, null, null, null, null, null)
			},
		)
	}
}
