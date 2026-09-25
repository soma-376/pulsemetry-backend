package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.analytics.UsageAggregator.Axis
import com.team376.pulsemetry.dashboard.analytics.UsageAggregator.Side
import com.team376.pulsemetry.dashboard.analytics.UsageAggregator.TeamScope
import com.team376.pulsemetry.dashboard.error.DashboardException
import com.team376.pulsemetry.dashboard.error.ErrorCode
import com.team376.pulsemetry.dashboard.error.FieldErrorCode
import com.team376.pulsemetry.dashboard.organization.Organization
import com.team376.pulsemetry.dashboard.request.ComparedPeriod
import com.team376.pulsemetry.dashboard.request.PageCursor
import com.team376.pulsemetry.dashboard.request.PageCursorCodec
import com.team376.pulsemetry.dashboard.request.PageRequest
import com.team376.pulsemetry.dashboard.snapshot.SnapshotService
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * 팀 분석 화면 (팀 분석 명세). 목록 → 상세 → 사용자 요청이 **같은 snapshot ID** 로 같은 입력을 읽는다(ADR 0023 §4). 처음 보는 endpoint 라서
 * 409 를 내지 않는다 — snapshot 이 유효하면 어느 endpoint 든 그 payload 의 부분 집합을 계산한다.
 *
 * - 팀 합계는 이벤트 당시 대표 팀(`team_id_as_of`)으로 한 번만 귀속한다. 미배정은 목록에 섞지 않고 `unassigned` 로 따로 낸다.
 * - 목록 정렬은 선택 지표 내림차순(null 마지막) + 팀 ID 오름차순. cursor 는 snapshot ID 와 목록 범위(endpoint·정렬·팀)에 묶인다 —
 *   다른 정렬·다른 팀·다른 snapshot 의 cursor 는 400 이다.
 * - 조직 합계와 모델 산점도는 페이지와 무관한 전체 권한 범위의 집계다.
 */
class TeamsService(
	private val frames: AnalyticsFrames,
	private val aggregator: UsageAggregator,
	private val references: SnapshotReferences,
	private val snapshots: SnapshotService,
	private val codec: PageCursorCodec,
) {

	enum class Sort(val wire: String) {
		COST("cost"), TOKEN("token"), SESSION("session");

		companion object {
			val BY_WIRE = entries.associateBy { it.wire }
		}
	}

	/** 경로의 팀. 예약값 `unassigned` 는 미배정이다. */
	sealed interface TeamKey {
		data class Team(val id: UUID) : TeamKey
		data object Unassigned : TeamKey

		companion object {
			/** 팀 ID 도 예약값도 아니면 null — 그런 팀은 없다(호출자가 권한 검사 뒤 404 로 낸다). */
			fun parseOrNull(value: String): TeamKey? = when {
				value == UNASSIGNED -> Unassigned
				else -> runCatching { UUID.fromString(value) }.getOrNull()?.takeIf { it.toString() == value.lowercase() }?.let(::Team)
			}

			const val UNASSIGNED = "unassigned"
		}
	}

	fun teams(
		organization: Organization,
		requestedBy: UUID,
		period: ComparedPeriod,
		sort: Sort,
		page: PageRequest,
		snapshotId: String?,
	): TeamsResponse {
		val frame = frames.frame(organization, requestedBy, period, usesComparison = true, snapshotId ?: page.cursor?.snapshotId)
		val scope = "analytics-teams:sort=${sort.wire}"
		page.cursor?.let { snapshots.requireCursor(it, frame.snapshot.snapshotId, scope) }
		val data = TeamData.load(frame, aggregator, references)

		val ranked = data.teamIds().sortedWith(
			compareBy<String, BigDecimal?>(nullsLast(reverseOrder())) { metric(data.current.getValue(listOf(it)), sort, frame.pricingMixed) }
				.thenBy { it },
		)
		val start = page.cursor?.let { cursor -> startAfter(ranked, cursor) } ?: 0
		val items = ranked.drop(start).take(page.limit)
		val next = if (start + items.size < ranked.size) {
			val last = items.last()
			codec.encode(PageCursor(frame.snapshot.snapshotId, scope, listOf(metric(data.current.getValue(listOf(last)), sort, frame.pricingMixed)?.toPlainString(), last)))
		} else null

		return TeamsResponse(
			meta = frames.analyticsMeta(frame),
			comparison = frames.comparison(frame),
			ingest = frames.ingest(frame),
			attributionBasis = ATTRIBUTION_BASIS,
			totals = OverviewResponse.UsagePair(
				current = if (frame.empty) null else Usage.of(data.organization, frame.pricingMixed),
				previous = data.previousOrganization?.let { Usage.of(it, frame.pricingMixed) },
			),
			sort = sort.wire,
			teams = Page(items.map { data.analytics(TeamKey.Team(UUID.fromString(it))) }, ranked.size, next),
			unassigned = data.analytics(TeamKey.Unassigned),
			modelScatter = data.modelScatter(),
		)
	}

	fun team(organization: Organization, requestedBy: UUID, key: TeamKey, period: ComparedPeriod, snapshotId: String?): TeamDetailResponse {
		val frame = frames.frame(organization, requestedBy, period, usesComparison = true, snapshotId)
		val data = TeamData.load(frame, aggregator, references)
		data.requireKnown(key)
		return TeamDetailResponse(frames.analyticsMeta(frame), frames.comparison(frame), data.analytics(key))
	}

	fun users(
		organization: Organization,
		requestedBy: UUID,
		key: TeamKey,
		period: ComparedPeriod,
		page: PageRequest,
		snapshotId: String?,
	): TeamUsersResponse {
		val frame = frames.frame(organization, requestedBy, period, usesComparison = false, snapshotId ?: page.cursor?.snapshotId)
		val teamScope = TeamScope((key as? TeamKey.Team)?.id?.toString())
		val scope = "analytics-team-users:team=${teamScope.teamId ?: TeamKey.UNASSIGNED}"
		page.cursor?.let { snapshots.requireCursor(it, frame.snapshot.snapshotId, scope) }
		val directory = references.teams(frame.snapshot).associateBy { it.id.toString() }

		val snapshot = frame.snapshot
		val pricingMixed = frame.pricingMixed
		val team = if (frame.empty) UsageTotals.EMPTY else aggregator.totals(snapshot, Side.CURRENT, Axis.ORGANIZATION, teamScope).getValue(emptyList())
		if (key is TeamKey.Team && key.id.toString() !in directory && !team.hasUsage) throw DashboardException(ErrorCode.NOT_FOUND)
		val members = if (frame.empty) emptyMap() else aggregator.totals(snapshot, Side.CURRENT, Axis.MEMBER, teamScope)
		val memberModels = if (frame.empty) emptyMap() else aggregator.totals(snapshot, Side.CURRENT, Axis.MEMBER_MODEL, teamScope)

		val identified = members.filterKeys { it.single() != null }.map { (k, v) -> k.single()!! to v }
		val ranked = identified.sortedWith(
			compareBy<Pair<String, UsageTotals>, BigDecimal?>(nullsLast(reverseOrder())) { it.second.equivalentCost(pricingMixed) }.thenBy { it.first },
		).map { it.first }
		val start = page.cursor?.let { startAfter(ranked, it) } ?: 0
		val items = ranked.drop(start).take(page.limit)
		val next = if (start + items.size < ranked.size) {
			val last = items.last()
			codec.encode(PageCursor(snapshot.snapshotId, scope, listOf(members.getValue(listOf(last)).equivalentCost(pricingMixed)?.toPlainString(), last)))
		} else null
		val accounts = references.accounts(snapshot, items.map(UUID::fromString))

		val users = items.map { memberId ->
			val totals = members.getValue(listOf(memberId))
			TeamUser(
				memberId = memberId,
				account = accounts[UUID.fromString(memberId)] ?: memberId,
				usage = Usage.of(totals, pricingMixed),
				mainModel = mainModel(memberModels, memberId, pricingMixed),
				cache = CacheUsage(totals.tokens(totals.cacheRead), totals.cacheEligibleInput(), totals.cacheHitRatio()),
				lastUsedAt = totals.lastSourceTime?.toString(),
			)
		}
		val unidentified = members[listOf(null)]
		return TeamUsersResponse(
			meta = frames.analyticsMeta(frame),
			team = teamRef(key, directory),
			summary = TeamUsersSummary(
				usage = if (frame.empty || !team.hasUsage) null else Usage.of(team, pricingMixed),
				averageEquivalentCostUsd = average(identified.map { it.second }, pricingMixed)?.let(Money::format),
				cacheReadTokens = team.tokens(team.cacheRead),
				cacheEligibleInputTokens = team.cacheEligibleInput(),
				cacheHitRatio = team.cacheHitRatio(),
				unidentifiedEquivalentCostUsd = unidentified?.equivalentCost(pricingMixed)?.let(Money::format),
			),
			users = Page(users, ranked.size, next),
		)
	}

	/** 사용자 안에서 금액이 가장 큰 모델(동률 ID 오름차순). 금액을 산출할 수 없으면 없다. */
	private fun mainModel(memberModels: Map<List<String?>, UsageTotals>, memberId: String, pricingMixed: Boolean): ModelRef? {
		val candidates = memberModels.filterKeys { it[0] == memberId }.map { (k, v) -> k[1]!! to v.equivalentCost(pricingMixed) }
		if (candidates.isEmpty() || candidates.any { it.second == null }) return null
		val (modelId, _) = candidates.sortedWith(compareByDescending<Pair<String, BigDecimal?>> { it.second }.thenBy { it.first }).first()
		return ModelRef(modelId, ModelNames.displayName(modelId))
	}

	/** 평균 = 식별된 사용자 비용 합 / 식별된 활성 사용자 수. 누구의 금액이든 없으면 없다. */
	private fun average(users: List<UsageTotals>, pricingMixed: Boolean): BigDecimal? {
		if (users.isEmpty()) return null
		val costs = users.map { it.equivalentCost(pricingMixed) ?: return null }
		return costs.fold(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal(users.size), 12, RoundingMode.HALF_EVEN)
	}

	/** cursor 의 마지막 키(ID) 다음 위치. 같은 snapshot 의 같은 순서에서 찾지 못하면 이 요청의 cursor 가 아니다. */
	private fun startAfter(ranked: List<String>, cursor: PageCursor): Int {
		val last = cursor.after.lastOrNull()
		val index = ranked.indexOf(last)
		if (index < 0) throw DashboardException.invalid(CURSOR, FieldErrorCode.INVALID_CURSOR)
		return index + 1
	}

	/** 정렬 지표. 비용·토큰·세션을 한 타입으로 비교한다. */
	private fun metric(totals: UsageTotals, sort: Sort, pricingMixed: Boolean): BigDecimal? = when (sort) {
		Sort.COST -> totals.equivalentCost(pricingMixed)
		Sort.TOKEN -> totals.apiTotal()?.let(::BigDecimal)
		Sort.SESSION -> totals.sessionCount()?.let(::BigDecimal)
	}

	/** 한 frame 에서 팀 화면이 쓰는 축별 집계를 한 번씩만 센다. */
	private class TeamData(
		val frame: AnalyticsFrames.Frame,
		val organization: UsageTotals,
		val previousOrganization: UsageTotals?,
		val current: Map<List<String?>, UsageTotals>,
		val previous: Map<List<String?>, UsageTotals>,
		val teamModels: Map<List<String?>, UsageTotals>,
		val teamDays: Map<List<String?>, UsageTotals>,
		val models: Map<List<String?>, UsageTotals>,
		val directory: Map<String, SnapshotReferences.Team>,
	) {
		val pricingMixed = frame.pricingMixed

		fun teamIds(): List<String> = current.keys.mapNotNull { it.single() }

		fun requireKnown(key: TeamKey) {
			if (key is TeamKey.Team && key.id.toString() !in directory && listOf(key.id.toString()) !in current) {
				throw DashboardException(ErrorCode.NOT_FOUND)
			}
		}

		fun analytics(key: TeamKey): TeamAnalytics {
			val id = (key as? TeamKey.Team)?.id?.toString()
			val totals = current[listOf(id)] ?: UsageTotals.EMPTY
			val ref = teamRef(key, directory)
			val models = teamModels.filterKeys { it[0] == id }
				.map { (k, v) -> modelUsage(k[1]!!, v, pricingMixed) }
				.sortedWith(COST_THEN_ID)
			return TeamAnalytics(
				teamId = ref.teamId,
				teamName = ref.teamName,
				current = if (frame.empty || !totals.hasUsage) null else Usage.of(totals, pricingMixed),
				previous = if (frame.comparable) previous[listOf(id)]?.takeIf { it.hasUsage }?.let { Usage.of(it, pricingMixed) } else null,
				modelMix = section(models) { TeamModelMix.of(models) },
				trend = frame.period.current.dates().map { date ->
					val day = teamDays[listOf(id, date.toString())] ?: UsageTotals.EMPTY
					val seen = frame.observed(date)
					TeamTrendPoint(
						date = date.toString(),
						observation = if (seen) PARTIAL else UNOBSERVED,
						equivalentCostUsd = if (seen) day.equivalentCost(pricingMixed)?.let(Money::format) else null,
						totalTokens = if (seen) day.apiTotal() else null,
						cumulativeSessionCount = null,
					)
				},
			)
		}

		/**
		 * 모델 산점도 — 조직 전체(미배정 포함)의 모델 수치와, 그 모델 비용이 팀 비용의 5% 이상인 실제 팀 수. 판정에 쓸 금액이 하나라도 없으면
		 * 그 모델의 팀 수는 없다.
		 */
		fun modelScatter(): Section<ModelScatter> {
			val scatter = models.map { (key, totals) ->
				val modelId = key.single()!!
				val usage = modelUsage(modelId, totals, pricingMixed)
				ScatterModel(usage.modelId, usage.displayName, usage.equivalentCostUsd, usage.totalTokens, usingTeamCount(modelId))
			}.sortedWith(compareBy<ScatterModel, BigDecimal?>(nullsLast(reverseOrder())) { it.equivalentCostUsd?.let(::BigDecimal) }.thenBy { it.modelId })
			if (scatter.isEmpty()) return Section(Availability.UNAVAILABLE, Availability.SOURCE_NOT_AVAILABLE, null)
			val complete = scatter.all { it.equivalentCostUsd != null && it.totalTokens != null && it.usingTeamCount != null }
			return Section(
				if (complete) Availability.AVAILABLE else Availability.PARTIAL,
				if (complete) null else Availability.SOURCE_NOT_AVAILABLE,
				ModelScatter(COST_SHARE_THRESHOLD, scatter),
			)
		}

		private fun usingTeamCount(modelId: String): Long? {
			val pairs = teamModels.filterKeys { it[0] != null && it[1] == modelId }
			var count = 0L
			for ((key, modelTotals) in pairs) {
				val modelCost = modelTotals.equivalentCost(pricingMixed) ?: return null
				val teamCost = current[listOf(key[0])]?.equivalentCost(pricingMixed)?.takeIf { it.signum() > 0 } ?: return null
				if (modelCost.divide(teamCost, 12, RoundingMode.HALF_EVEN) >= THRESHOLD) count++
			}
			return count
		}

		companion object {
			fun load(frame: AnalyticsFrames.Frame, aggregator: UsageAggregator, references: SnapshotReferences): TeamData {
				val snapshot = frame.snapshot
				val empty = frame.empty
				fun totals(axis: Axis, side: Side = Side.CURRENT) = if (empty) emptyMap() else aggregator.totals(snapshot, side, axis)
				return TeamData(
					frame = frame,
					organization = aggregator.totals(snapshot, Side.CURRENT, Axis.ORGANIZATION).getValue(emptyList()),
					previousOrganization = if (frame.comparable) aggregator.totals(snapshot, Side.PREVIOUS, Axis.ORGANIZATION).getValue(emptyList()) else null,
					current = totals(Axis.TEAM),
					previous = if (frame.comparable) totals(Axis.TEAM, Side.PREVIOUS) else emptyMap(),
					teamModels = totals(Axis.TEAM_MODEL),
					teamDays = totals(Axis.TEAM_DAY),
					models = totals(Axis.MODEL),
					directory = references.teams(snapshot).associateBy { it.id.toString() },
				)
			}

			val THRESHOLD: BigDecimal = BigDecimal("0.05")
		}
	}

	companion object {
		/** 사용 행의 source_time 당시 대표 팀에 귀속한다(ADR 0023 §4). */
		const val ATTRIBUTION_BASIS = "event_time"
		const val COST_SHARE_THRESHOLD = 0.05
		const val UNASSIGNED_NAME = "미배정"
		private const val PARTIAL = "partial"
		private const val UNOBSERVED = "unobserved"
		private const val CURSOR = "cursor"

		private val COST_THEN_ID = compareBy<TeamModelUsage, BigDecimal?>(nullsLast(reverseOrder())) { it.equivalentCostUsd?.let(::BigDecimal) }
			.thenBy { it.modelId }

		fun teamRef(key: TeamKey, directory: Map<String, SnapshotReferences.Team>): TeamRef = when (key) {
			TeamKey.Unassigned -> TeamRef(null, UNASSIGNED_NAME)
			is TeamKey.Team -> TeamRef(key.id.toString(), directory[key.id.toString()]?.name ?: key.id.toString())
		}

		fun modelUsage(modelId: String, totals: UsageTotals, pricingMixed: Boolean) = TeamModelUsage(
			modelId = modelId,
			displayName = ModelNames.displayName(modelId),
			equivalentCostUsd = totals.equivalentCost(pricingMixed)?.let(Money::format),
			totalTokens = totals.apiTotal(),
		)

		/** 모델이 없으면 unavailable(data 없음), 값 일부가 없으면 partial, 전부 있으면 available. */
		fun <T> section(models: List<TeamModelUsage>, data: () -> T): Section<T> = when {
			models.isEmpty() -> Section(Availability.UNAVAILABLE, Availability.SOURCE_NOT_AVAILABLE, null)
			models.all { it.equivalentCostUsd != null && it.totalTokens != null } -> Section(Availability.AVAILABLE, null, data())
			else -> Section(Availability.PARTIAL, Availability.SOURCE_NOT_AVAILABLE, data())
		}
	}
}
