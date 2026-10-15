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
import com.team376.pulsemetry.dashboard.request.OffsetPageRequest
import com.team376.pulsemetry.dashboard.snapshot.SnapshotService
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * 팀 분석 화면 (팀 분석 명세). 목록 → 상세 → 사용자 요청이 **같은 snapshot ID** 로 같은 입력을 읽는다(ADR 0023 §4). 처음 보는 endpoint 라서
 * 409 를 내지 않는다 — snapshot 이 유효하면 어느 endpoint 든 그 payload 의 부분 집합을 계산한다.
 *
 * - 팀 합계는 이벤트 당시 대표 팀(`team_id_as_of`)으로 한 번만 귀속한다. 미배정은 목록에 섞지 않고 `unassigned` 로 따로 낸다.
 * - 목록 정렬은 선택 지표 내림차순(null 마지막) + 팀 ID 오름차순. 같은 snapshot에서 offset으로 이어 읽는다.
 *   사용자 목록도 전체 정렬 후 offset으로 나누고, 정렬 변경 시 첫 페이지부터 요청한다.
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

	enum class UserSort(val wire: String) {
		COST("cost"), AVERAGE_DEVIATION("averageDeviation"), ACCOUNT("account"), SESSION("session"), TOKEN("token"), CACHE("cache"), LAST_USED("lastUsed");

		companion object {
			val BY_WIRE = entries.associateBy { it.wire }
		}
	}

	enum class UserOrder(val wire: String) {
		ASC("asc"), DESC("desc");

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
		page: OffsetPageRequest,
		snapshotId: String?,
	): TeamsResponse {
		val frame = frames.frame(organization, requestedBy, period, usesComparison = true, snapshotId)
		val data = TeamData.load(frame, aggregator, references)

		val ranked = data.teamIds().sortedWith(
			compareBy<String, BigDecimal?>(nullsLast(reverseOrder())) { metric(data.current.getValue(listOf(it)), sort, frame.pricingMixed) }
				.thenBy { it },
		)
		val result = OffsetPage.of(ranked, page.offset, page.limit)


		return TeamsResponse(
			meta = TeamMeta.of(frames.analyticsMeta(frame)),
			comparison = frames.comparison(frame),
			attributionBasis = ATTRIBUTION_BASIS,
			totals = TeamScreenValues.metrics(frame, data.organization, data.previousOrganization),
			sort = sort.wire,
			teams = OffsetPage(result.items.map { data.analytics(TeamKey.Team(UUID.fromString(it))) }, result.totalCount, result.nextOffset),
			unassigned = data.analytics(TeamKey.Unassigned),
			modelScatter = data.modelScatter(),
		)
	}

	fun team(organization: Organization, requestedBy: UUID, key: TeamKey, period: ComparedPeriod, snapshotId: String?): TeamDetailResponse {
		val frame = frames.frame(organization, requestedBy, period, usesComparison = true, snapshotId)
		val data = TeamData.load(frame, aggregator, references)
		data.requireKnown(key)
		return TeamDetailResponse(TeamMeta.of(frames.analyticsMeta(frame)), frames.comparison(frame), data.analytics(key))
	}

	fun users(
		organization: Organization,
		requestedBy: UUID,
		key: TeamKey,
		period: ComparedPeriod,
		page: OffsetPageRequest,
		snapshotId: String?,
		sort: UserSort = UserSort.COST,
		order: UserOrder = UserOrder.DESC,
	): TeamUsersResponse {
		val frame = frames.frame(organization, requestedBy, period, usesComparison = false, snapshotId)
		val teamScope = TeamScope((key as? TeamKey.Team)?.id?.toString())
		val directory = references.teams(frame.snapshot).associateBy { it.id.toString() }

		val snapshot = frame.snapshot
		val pricingMixed = frame.pricingMixed
		val team = if (frame.empty) UsageTotals.EMPTY else aggregator.totals(snapshot, Side.CURRENT, Axis.ORGANIZATION, teamScope).getValue(emptyList())
		if (key is TeamKey.Team && key.id.toString() !in directory && !team.hasUsage) throw DashboardException(ErrorCode.NOT_FOUND)
		val members = if (frame.empty) emptyMap() else aggregator.totals(snapshot, Side.CURRENT, Axis.MEMBER, teamScope)
		val memberModels = if (frame.empty) emptyMap() else aggregator.totals(snapshot, Side.CURRENT, Axis.MEMBER_MODEL, teamScope)

		val identified = members.filterKeys { it.single() != null }.map { (k, v) -> k.single()!! to v }
		val averageCost = average(identified.map { it.second }, pricingMixed)?.let(Money::format)
		// 평균 대비 = 개인 비용 / 팀 전체 평균 - 1. 양수인 공통 분모이므로 비용 원본으로 비교하면
		// 비율 반올림으로 동률을 만들지 않는다. 표시 평균이 0이거나 확인 불가이면 모두 비교 불가다.
		val comparableAverage = averageCost?.toBigDecimal()?.signum() == 1
		val accounts = references.accounts(snapshot, identified.map { UUID.fromString(it.first) })
		val allUsers = identified.map { (memberId, totals) ->
			TeamUser(memberId = memberId, account = accounts[UUID.fromString(memberId)] ?: memberId,
				usage = TeamScreenValues.user(frame, totals), mainModel = mainModel(memberModels, memberId, pricingMixed),
				cacheHitRatio = TeamScreenValues.cache(frame, totals), lastUsedAt = totals.lastSourceTime?.toString())
		}
		val decimals = if (order == UserOrder.ASC) naturalOrder<BigDecimal>() else reverseOrder<BigDecimal>()
		val comparator = when (sort) {
			UserSort.ACCOUNT -> compareBy<TeamUser, String>(if (order == UserOrder.ASC) naturalOrder() else reverseOrder()) { it.account.lowercase(java.util.Locale.ROOT) }
			UserSort.LAST_USED -> compareBy<TeamUser, java.time.Instant?>(nullsLast(if (order == UserOrder.ASC) naturalOrder() else reverseOrder())) { it.lastUsedAt?.let(java.time.Instant::parse) }
			else -> compareBy<TeamUser, BigDecimal?>(nullsLast(decimals)) { user -> when (sort) {
				UserSort.AVERAGE_DEVIATION -> if (comparableAverage) members.getValue(listOf(user.memberId)).equivalentCost(pricingMixed) else null
				UserSort.COST -> members.getValue(listOf(user.memberId)).equivalentCost(pricingMixed)
				UserSort.SESSION -> user.usage.sessionCount.value?.toBigDecimal()
				UserSort.TOKEN -> user.usage.totalTokens.value?.toBigDecimal()
				UserSort.CACHE -> user.cacheHitRatio.value?.toBigDecimal()
				else -> null
			} }
		}
		val result = OffsetPage.of(allUsers.sortedWith(comparator.thenBy { it.memberId }), page.offset, page.limit)

		val unidentified = members[listOf(null)]
		return TeamUsersResponse(
			meta = TeamMeta.of(frames.analyticsMeta(frame)),
			team = teamRef(key, directory),
			summary = TeamUsersSummary(
				// 완전한 기간이면 사용이 없는 팀도 0 이다 — 팀 상세와 같은 기준이다.
				usage = TeamScreenValues.summary(frame, team),
				averageEquivalentCostUsd = averageCost,
				cacheHitRatio = TeamScreenValues.cache(frame, team),
				unidentifiedEquivalentCostUsd = unidentified?.equivalentCost(pricingMixed)?.let(Money::format),
			),
			users = result,
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
		val teamProducts: Map<List<String?>, UsageTotals>,
		/** 팀별로 세션을 처음 본 날의 새 세션 수 — 누적 세션(대시보드 명세 "팀 누적 세션")의 재료. */
		val firstSessions: Map<Pair<String?, String>, Long>,
		val products: List<SnapshotReferences.Product>,
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
				.map { (k, v) -> TeamScreenValues.model(frame, k[1]!!, v) }
				.sortedWith(COST_THEN_ID)
			// 누적 세션(대시보드 명세 "팀 누적 세션"): 시작일부터 그날까지 모든 날이 완전하고 세션 없는 행이 없을 때만 값이다 — 한 번 끊기면 그 뒤로는 없다.
			var sessions = 0L
			val cumulativeReasons = linkedSetOf<String>()
			return TeamAnalytics(
				teamId = ref.teamId,
				teamName = ref.teamName,
				metrics = TeamScreenValues.metrics(frame, totals, if (frame.comparable) previous[listOf(id)] ?: UsageTotals.EMPTY else null),
				modelMix = section(models) { TeamModelMix.of(models) },
				trend = frame.period.current.dates().map { date ->
					val day = teamDays[listOf(id, date.toString())] ?: UsageTotals.EMPTY
					val observation = frame.observation(date)
					if (observation != Coverage.COMPLETE) {
						cumulativeReasons.add("cumulative_period_incomplete")
						cumulativeReasons.addAll(frame.dateReasons(date))
					}
					if (day.sessionlessRows > 0) cumulativeReasons.add("session_identity_missing")
					sessions += firstSessions[id to date.toString()] ?: 0
					TeamScreenValues.point(frame, date, day, if (cumulativeReasons.isEmpty()) sessions else null, cumulativeReasons.toList())
				},
				products = Products.usages(teamProducts.filterKeys { it[0] == id }.mapKeys { it.key[1] ?: UsageAggregator.UNMAPPED_PRODUCT }, products, pricingMixed),
			)
		}

		/**
		 * 모델 산점도 — 조직 전체(미배정 포함)의 모델 수치와, 그 모델 비용이 팀 비용의 5% 이상인 실제 팀 수. 판정에 쓸 금액이 하나라도 없으면
		 * 그 모델의 팀 수는 없다.
		 */
		fun modelScatter(): Section<ModelScatter> {
			val scatter = models.map { (key, totals) ->
				val modelId = key.single()!!
				val usage = TeamScreenValues.model(frame, modelId, totals)
				TeamScreenValues.scatter(usage, usingTeamCount(modelId))
			}.sortedWith(compareBy<ScatterModel, BigDecimal?>(nullsLast(reverseOrder())) { it.equivalentCostUsd?.let(::BigDecimal) }.thenBy { it.modelId })
			if (scatter.isEmpty()) return Section(Availability.UNAVAILABLE, Availability.SOURCE_NOT_AVAILABLE, null)
			val complete = scatter.all { it.status == "complete" }
			return Section(
				if (complete) Availability.AVAILABLE else Availability.PARTIAL,
				if (complete) null else scatter.flatMap { it.reasons }.firstOrNull() ?: Availability.SOURCE_NOT_AVAILABLE,
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
				val products = references.products(snapshot)
				fun totals(axis: Axis, side: Side = Side.CURRENT) = if (empty) emptyMap() else aggregator.totals(snapshot, side, axis, products = products)
				return TeamData(
					frame = frame,
					organization = if (empty) UsageTotals.EMPTY else aggregator.totals(snapshot, Side.CURRENT, Axis.ORGANIZATION).getValue(emptyList()),
					previousOrganization = if (frame.comparable) aggregator.totals(snapshot, Side.PREVIOUS, Axis.ORGANIZATION).getValue(emptyList()) else null,
					current = totals(Axis.TEAM),
					previous = if (frame.comparable) totals(Axis.TEAM, Side.PREVIOUS) else emptyMap(),
					teamModels = totals(Axis.TEAM_MODEL),
					teamDays = totals(Axis.TEAM_DAY),
					models = totals(Axis.MODEL),
					teamProducts = totals(Axis.TEAM_PRODUCT),
					firstSessions = if (empty) emptyMap() else aggregator.firstSessionDays(snapshot, Side.CURRENT),
					products = products,
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
		private const val CURSOR = "cursor"

		private val COST_THEN_ID = compareBy<TeamModelUsage, BigDecimal?>(nullsLast(reverseOrder())) { it.equivalentCostUsd?.let(::BigDecimal) }
			.thenBy { it.modelId }

		fun teamRef(key: TeamKey, directory: Map<String, SnapshotReferences.Team>): TeamRef = when (key) {
			TeamKey.Unassigned -> TeamRef(null, UNASSIGNED_NAME)
			is TeamKey.Team -> TeamRef(key.id.toString(), directory[key.id.toString()]?.name ?: key.id.toString())
		}

		/** 모델이 없으면 unavailable(data 없음), 값 일부가 없으면 partial, 전부 있으면 available. */
		fun <T> section(models: List<TeamModelUsage>, data: () -> T): Section<T> = when {
			models.isEmpty() -> Section(Availability.UNAVAILABLE, Availability.SOURCE_NOT_AVAILABLE, null)
			models.all { it.status == "complete" } -> Section(Availability.AVAILABLE, null, data())
			else -> Section(Availability.PARTIAL, models.flatMap { it.reasons }.firstOrNull() ?: Availability.SOURCE_NOT_AVAILABLE, data())
		}
	}
}
