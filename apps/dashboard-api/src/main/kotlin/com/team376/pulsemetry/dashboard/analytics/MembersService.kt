package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.analytics.UsageAggregator.Axis
import com.team376.pulsemetry.dashboard.analytics.UsageAggregator.Side
import com.team376.pulsemetry.dashboard.authentication.Roles
import com.team376.pulsemetry.dashboard.error.DashboardException
import com.team376.pulsemetry.dashboard.error.FieldErrorCode
import com.team376.pulsemetry.dashboard.organization.Organization
import com.team376.pulsemetry.dashboard.request.ComparedPeriod
import com.team376.pulsemetry.dashboard.request.PageCursor
import com.team376.pulsemetry.dashboard.request.PageCursorCodec
import com.team376.pulsemetry.dashboard.request.PageRequest
import com.team376.pulsemetry.dashboard.request.QueryReader
import com.team376.pulsemetry.dashboard.snapshot.SnapshotService
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Clock
import java.util.Locale
import java.util.UUID

/**
 * 구성원 화면의 조회 (구성원 명세). **로스터가 원천**이다 — 사용 이벤트가 없는 사람도 목록에 남는다. 로스터와 현재 팀은 snapshot 에 고정한
 * 복제본(ADR 0023 §2), 기간 사용량은 공통 계산기의 구성원 축, 마지막 사용은 build 때 고정한 asOf 결과다.
 *
 * - **세 `lastUsedAt` 은 범위가 다르다.** 여기의 `Member.lastUsedAt` 은 asOf 까지·팀과 무관한 마지막 사용이다. 팀 사용자 표의 값은 선택 기간 안·
 *   그 팀의 마지막 사용이고, 회수 후보의 값은 검증된 공급자·계약 범위의 마지막 사용이다(좌석 원천이 없어 지금은 내지 않는다).
 * - 현재 팀은 로스터의 현재 소속이다 — 이벤트 당시 귀속 팀과 다르다. 현재 팀이 없으면 미배정이다. 현재 소속이 여럿이면 하나를 고르지 않는다:
 *   팀 ID 는 null 이고 이름은 소속 팀 이름을 모두 적는다(대표 팀을 고르는 규칙을 만들지 않는다).
 * - 로스터는 `active`·`suspended` 구성원이다. 초대 중(`invited`)인 사람은 로스터가 아니다.
 * - 좌석·회수 후보는 계약·좌석 배정 원천이 화면의 좌석 모델과 맞지 않아 지원하지 않는다(`unavailable` + `not_applicable`). 쓰기 기능은 모두 비활성이다.
 * - 목록은 선택 기간 비용 내림차순(수집되지 않은 사람은 null 로 마지막) + memberId 오름차순. 검색은 첫 페이지가 아니라 로스터 전체에 적용한다.
 */
class MembersService(
	private val frames: AnalyticsFrames,
	private val aggregator: UsageAggregator,
	private val references: SnapshotReferences,
	private val snapshots: SnapshotService,
	private val codec: PageCursorCodec,
	private val tokens: CurrentStateTokens,
	private val idleDays: Int,
	private val clock: Clock,
	private val managementEnabled: Boolean = false,
) {

	private val log = LoggerFactory.getLogger(MembersService::class.java)

	fun dashboard(organization: Organization, requestedBy: UUID, period: ComparedPeriod): MembersResponse {
		val frame = frames.frame(organization, requestedBy, period, usesComparison = false, snapshotId = null)
		val view = view(frame)
		val first = PageRequest(DASHBOARD_LIMIT, null)
		return MembersResponse(
			meta = frames.analyticsMeta(frame),
			asOf = frame.snapshot.asOf.toString(),
			ingest = frames.ingest(frame),
			summary = MemberSummary(
				rosterMembers = view.roster.size.toLong(),
				activeUsers = if (frame.empty) null else view.organization.activeUsers(),
				unassignedMembers = view.roster.count { it.currentTeamIds.isEmpty() }.toLong(),
				periodUnassignedEquivalentCostUsd = if (frame.empty) null else view.unassignedUsage.equivalentCost(frame.pricingMixed)?.let(Money::format),
				periodTotalEquivalentCostUsd = if (frame.empty) null else view.organization.equivalentCost(frame.pricingMixed)?.let(Money::format),
				seats = Section(Availability.UNAVAILABLE, Availability.NOT_APPLICABLE, null),
			),
			policy = IdlePolicy(idleDays, POLICY_VERSION),
			capabilities = MemberCapabilities(invite = managementEnabled, assignTeam = managementEnabled, reclaimSeats = false, restoreSeats = false),
			members = page(view, view.roster, first, scope(null)),
			unassigned = page(view, view.roster.filter { it.currentTeamIds.isEmpty() }, first, UNASSIGNED_SCOPE),
			reclaimCandidates = Section(Availability.UNAVAILABLE, Availability.NOT_APPLICABLE, null),
		)
	}

	fun members(organization: Organization, requestedBy: UUID, period: ComparedPeriod, search: String?, page: PageRequest, snapshotId: String?): MemberListResponse {
		val frame = frames.frame(organization, requestedBy, period, usesComparison = false, snapshotId ?: page.cursor?.snapshotId)
		val scope = scope(search)
		page.cursor?.let { snapshots.requireCursor(it, frame.snapshot.snapshotId, scope) }
		val view = view(frame)
		val needle = search?.lowercase(Locale.ROOT)
		val matched = if (needle == null) view.roster else view.roster.filter {
			it.account.lowercase(Locale.ROOT).contains(needle) || it.displayName?.lowercase(Locale.ROOT)?.contains(needle) == true
		}
		return MemberListResponse(frames.analyticsMeta(frame), page(view, matched, page, scope))
	}

	fun unassigned(organization: Organization, requestedBy: UUID, period: ComparedPeriod, page: PageRequest, snapshotId: String?): MemberListResponse {
		val frame = frames.frame(organization, requestedBy, period, usesComparison = false, snapshotId ?: page.cursor?.snapshotId)
		page.cursor?.let { snapshots.requireCursor(it, frame.snapshot.snapshotId, UNASSIGNED_SCOPE) }
		val view = view(frame)
		return MemberListResponse(frames.analyticsMeta(frame), page(view, view.roster.filter { it.currentTeamIds.isEmpty() }, page, UNASSIGNED_SCOPE))
	}

	/**
	 * 회수 후보 — 좌석 배정 원천이 없어 목록을 만들지 않는다. 미확인 공급자의 사용 가능성을 무시하고 idle 로 확정하지 않는다.
	 * 현재 상태 목록이라 snapshot ID 는 [CurrentStateTokens] 이다. 후보가 없으므로 어떤 cursor 도 이 목록의 것이 아니다(400).
	 */
	fun reclaimCandidates(organization: Organization, page: PageRequest, snapshotId: String?): ReclaimCandidatesResponse {
		val now = clock.instant()
		val token = tokens.resolve(RECLAIM_KIND, organization.id, snapshotId ?: page.cursor?.snapshotId, now)
		if (page.cursor != null) throw DashboardException.invalid(CURSOR, FieldErrorCode.INVALID_CURSOR)
		return ReclaimCandidatesResponse(
			meta = CurrentMeta(organization.id.toString(), now.toString(), token.asOf.toString(), token.value, AnalyticsFrames.USD, QueryReader.SEOUL_ID),
			idleDays = idleDays,
			candidates = Section(Availability.UNAVAILABLE, Availability.NOT_APPLICABLE, null),
		)
	}

	private fun page(view: RosterView, candidates: List<SnapshotReferences.RosterMember>, page: PageRequest, scope: String): Page<Member> {
		val ranked = candidates.sortedWith(
			compareBy<SnapshotReferences.RosterMember, BigDecimal?>(nullsLast(reverseOrder())) { view.cost(it.id) }.thenBy { it.id.toString() },
		)
		val start = page.cursor?.let { cursor ->
			val index = ranked.indexOfFirst { it.id.toString() == cursor.after.lastOrNull() }
			if (index < 0) throw DashboardException.invalid(CURSOR, FieldErrorCode.INVALID_CURSOR)
			index + 1
		} ?: 0
		val items = ranked.drop(start).take(page.limit)
		val next = if (start + items.size < ranked.size) {
			val last = items.last()
			codec.encode(PageCursor(view.frame.snapshot.snapshotId, scope, listOf(view.cost(last.id)?.toPlainString(), last.id.toString())))
		} else null
		return Page(items.map(view::member), ranked.size, next)
	}

	private fun scope(search: String?) = "members:q=" + if (search == null) "-" else
		MessageDigest.getInstance("SHA-256").digest(search.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

	/** 한 frame 의 로스터와 사용량을 한 번씩 읽는다. */
	private inner class RosterView(
		val frame: AnalyticsFrames.Frame,
		val roster: List<SnapshotReferences.RosterMember>,
		val usage: Map<List<String?>, UsageTotals>,
		val lastUsed: Map<String, java.time.Instant>,
		val teams: Map<UUID, SnapshotReferences.Team>,
		val organization: UsageTotals,
		val unassignedUsage: UsageTotals,
	) {
		fun totals(id: UUID): UsageTotals = usage[listOf(id.toString())] ?: UsageTotals.EMPTY

		fun cost(id: UUID): BigDecimal? = if (frame.empty) null else totals(id).equivalentCost(frame.pricingMixed)

		fun member(roster: SnapshotReferences.RosterMember): Member {
			val totals = totals(roster.id)
			return Member(
				memberId = roster.id.toString(),
				account = roster.account,
				displayName = roster.displayName ?: roster.account,
				team = currentTeam(roster),
				role = Roles.of(roster.role).wire,
				status = roster.status,
				version = roster.updatedAt.toEpochMilli(),
				periodUsage = if (frame.empty || !totals.hasUsage) null else Usage.of(totals, frame.pricingMixed),
				lastUsedAt = lastUsed[roster.id.toString()]?.toString(),
				observation = if (frame.currentCoverage.observedDays > 0) PARTIAL else UNOBSERVED,
				seatState = SEAT_UNKNOWN,
			)
		}

		private fun currentTeam(roster: SnapshotReferences.RosterMember): TeamRef = when (roster.currentTeamIds.size) {
			0 -> TeamRef(null, TeamsService.UNASSIGNED_NAME)
			1 -> roster.currentTeamIds.single().let { TeamRef(it.toString(), teams[it]?.name ?: it.toString()) }
			else -> {
				log.info("구성원 {} 의 현재 소속이 {}개 — 하나를 고르지 않는다", roster.id, roster.currentTeamIds.size)
				TeamRef(null, roster.currentTeamIds.map { teams[it]?.name ?: it.toString() }.sorted().joinToString(", "))
			}
		}
	}

	private fun view(frame: AnalyticsFrames.Frame): RosterView {
		val snapshot = frame.snapshot
		val teamTotals = if (frame.empty) emptyMap() else aggregator.totals(snapshot, Side.CURRENT, Axis.TEAM)
		return RosterView(
			frame = frame,
			roster = references.roster(snapshot).filter { it.status in ROSTER_STATUSES },
			usage = if (frame.empty) emptyMap() else aggregator.totals(snapshot, Side.CURRENT, Axis.MEMBER),
			lastUsed = references.lastUsed(snapshot),
			teams = references.teams(snapshot).associateBy { it.id },
			organization = aggregator.totals(snapshot, Side.CURRENT, Axis.ORGANIZATION).getValue(emptyList()),
			unassignedUsage = teamTotals[listOf(null)] ?: UsageTotals.EMPTY,
		)
	}

	companion object {
		const val DASHBOARD_LIMIT = 20
		/** 저장된 유휴 정책이 없다 — 판 0 은 "정책 저장소에서 온 값이 아님"이다. 유휴 일수는 설정이다. */
		const val POLICY_VERSION = 0L
		const val RECLAIM_KIND = "seat-reclaim-candidates"
		private const val UNASSIGNED_SCOPE = "members-unassigned"
		private const val CURSOR = "cursor"
		private const val PARTIAL = "partial"
		private const val UNOBSERVED = "unobserved"
		private const val SEAT_UNKNOWN = "unknown"
		private val ROSTER_STATUSES = setOf("active", "suspended")

		/** 쓰기 API 가 없다 — 초대·배정·회수·복원 모두 비활성. */
	}
}
