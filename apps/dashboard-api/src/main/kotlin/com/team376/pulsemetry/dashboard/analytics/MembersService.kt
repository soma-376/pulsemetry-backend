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
 *   그 팀의 마지막 사용이고, 회수 후보의 값은 그 좌석 제품(관측 제품 매핑)의 마지막 사용과 벤더 활동 중 늦은 것이다.
 * - 현재 팀은 로스터의 현재 소속이다 — 이벤트 당시 귀속 팀과 다르다. 현재 팀이 없으면 미배정이다. 현재 소속이 여럿이면 하나를 고르지 않는다:
 *   팀 ID 는 null 이고 이름은 소속 팀 이름을 모두 적는다(대표 팀을 고르는 규칙을 만들지 않는다).
 * - 로스터는 `active`·`suspended` 구성원이다. 초대 중(`invited`)인 사람은 로스터가 아니다.
 * - 좌석(요약·`seatState`·회수 후보·구성원 좌석)은 좌석 원장(ADR 0048)을 기준 시각으로 다시 세운 값이다([SeatService]). 구성원 화면의 기준 시각은
 *   snapshot 의 asOf 이고, 회수 후보의 다음 페이지는 같은 시각의 현재 상태 토큰으로 잇는다. 회수 가능 여부는 좌석마다 낸다(ADR 0049).
 * - 목록은 선택 기간 비용 내림차순(수집되지 않은 사람은 null 로 마지막) + memberId 오름차순. 검색은 첫 페이지가 아니라 로스터 전체에 적용한다.
 */
class MembersService(
	private val frames: AnalyticsFrames,
	private val aggregator: UsageAggregator,
	private val references: SnapshotReferences,
	private val snapshots: SnapshotService,
	private val codec: PageCursorCodec,
	private val tokens: CurrentStateTokens,
	private val policies: OrganizationPolicies,
	private val clock: Clock,
	private val managementEnabled: Boolean = false,
	private val seats: SeatService,
) {

	private val log = LoggerFactory.getLogger(MembersService::class.java)

	fun dashboard(organization: Organization, requestedBy: UUID, period: ComparedPeriod): MembersResponse {
		val frame = frames.frame(organization, requestedBy, period, usesComparison = false, snapshotId = null)
		val token = tokens.at(RECLAIM_KIND, organization.id, frame.snapshot.asOf)
		val view = view(frame, seats.assess(organization.id, token.asOf, references.roster(frame.snapshot).filter { it.status in ROSTER_STATUSES }.map { it.id }.toSet()))
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
				seats = seatSummary(view),
			),
			policy = policy(organization),
			// 회수·복원은 관리 기능이 있으면 된다 — 벤더 제어가 없는 좌석은 관리자 조치 확인으로 끝난다(ADR 0049). 좌석마다의 가능 여부는 따로 낸다.
			capabilities = MemberCapabilities(invite = managementEnabled, assignTeam = managementEnabled, reclaimSeats = managementEnabled, restoreSeats = managementEnabled),
			members = page(view, view.roster, first, scope(null)),
			unassigned = page(view, view.roster.filter { it.currentTeamIds.isEmpty() }, first, UNASSIGNED_SCOPE),
			reclaimCandidates = candidatePage(view.seats!!, view.roster.associate { it.id to (it.account to view.currentTeam(it)) }, first, token),
		)
	}

	fun members(organization: Organization, requestedBy: UUID, period: ComparedPeriod, search: String?, page: PageRequest, snapshotId: String?): MemberListResponse {
		val frame = frames.frame(organization, requestedBy, period, usesComparison = false, snapshotId ?: page.cursor?.snapshotId)
		val scope = scope(search)
		page.cursor?.let { snapshots.requireCursor(it, frame.snapshot.snapshotId, scope) }
		val view = view(frame, null)
		val needle = search?.lowercase(Locale.ROOT)
		val matched = if (needle == null) view.roster else view.roster.filter {
			it.account.lowercase(Locale.ROOT).contains(needle) || it.displayName?.lowercase(Locale.ROOT)?.contains(needle) == true
		}
		return MemberListResponse(frames.analyticsMeta(frame), page(view, matched, page, scope))
	}

	fun unassigned(organization: Organization, requestedBy: UUID, period: ComparedPeriod, page: PageRequest, snapshotId: String?): MemberListResponse {
		val frame = frames.frame(organization, requestedBy, period, usesComparison = false, snapshotId ?: page.cursor?.snapshotId)
		page.cursor?.let { snapshots.requireCursor(it, frame.snapshot.snapshotId, UNASSIGNED_SCOPE) }
		val view = view(frame, null)
		return MemberListResponse(frames.analyticsMeta(frame), page(view, view.roster.filter { it.currentTeamIds.isEmpty() }, page, UNASSIGNED_SCOPE))
	}

	/**
	 * 회수 후보 (ADR 0048 §7, [SeatAssessment]) — 현재 상태 목록이라 snapshot ID 는 [CurrentStateTokens] 이고, 토큰의 기준 시각으로 원장을 다시 세운다.
	 * 유휴 일수 내림차순 + 좌석 ID 오름차순. 사용 이벤트가 없다는 것만으로 후보로 만들지 않는다 — 관측할 수 없던 좌석은 목록에서 빼고 `partial` 이다.
	 */
	fun reclaimCandidates(organization: Organization, page: PageRequest, snapshotId: String?): ReclaimCandidatesResponse {
		val now = clock.instant()
		val token = tokens.resolve(RECLAIM_KIND, organization.id, snapshotId ?: page.cursor?.snapshotId, now)
		val policy = policy(organization)
		val roster = seats.roster(organization.id, token.asOf)
		val teams = seats.teamNames(organization.id)
		val assessment = seats.assess(organization.id, token.asOf, roster.keys)
		return ReclaimCandidatesResponse(
			meta = CurrentMeta(organization.id.toString(), now.toString(), token.asOf.toString(), token.value, AnalyticsFrames.USD, QueryReader.SEOUL_ID),
			idleDays = policy.idleDays,
			candidates = candidatePage(assessment, roster.mapValues { (_, r) -> r.account to teamRef(r.teamIds, teams) }, page, token),
			policy = policy,
		)
	}

	/**
	 * 구성원의 벤더 좌석 (`GET O/members/{memberId}/seats`). 현재 상태라 토큰의 기준 시각으로 원장을 다시 세운다. 로스터에 없는 구성원은 404.
	 * 보관한 등록 제품의 좌석은 싣지 않는다. 보유 좌석이 먼저, 그다음 제품 이름·계정 순서다.
	 */
	fun memberSeats(organization: Organization, memberId: UUID, snapshotId: String?): MemberSeatsResponse {
		val now = clock.instant()
		val token = tokens.resolve(MEMBER_SEATS_KIND, organization.id, snapshotId, now)
		val roster = seats.roster(organization.id, token.asOf)
		if (memberId !in roster) throw DashboardException(com.team376.pulsemetry.dashboard.error.ErrorCode.NOT_FOUND)
		val assessment = seats.assess(organization.id, token.asOf, roster.keys)
		val states = assessment.products.associateBy { it.product.vendorId }
		val items = assessment.ledger.seats.filter { it.memberId == memberId }.mapNotNull { seat ->
			val state = states[seat.vendorId] ?: return@mapNotNull null
			val product = state.product
			val review = if (seat.state == com.team376.pulsemetry.persistence.enrollment.seat.SeatState.ASSIGNED) assessment.review(seat) else null
			val reclaim = assessment.reclaim(seat, managementEnabled)
			MemberSeat(
				seatAssignmentId = seat.id.toString(), version = seat.version, vendorId = seat.vendorId, vendorName = product.displayName, kind = product.kind,
				contractVersion = product.contract?.version, tierId = seat.tierId, tierLabel = product.tier(seat.tierId)?.label, vendorTier = seat.vendorTier,
				account = seat.account, accountKind = seat.accountKind.wire, state = seat.state.wire, source = seat.source.wire, memberLink = seat.memberLink?.wire,
				assignedAt = seat.assignedAt.toString(), releaseEffectiveOn = seat.releaseEffectiveOn?.toString(), releasedAt = seat.releasedAt?.toString(),
				ledgerAvailability = state.availability, ledgerReason = state.reason,
				lastUsedAt = review?.lastUsedAt?.toString(), idleDays = review?.idleDays, reviewReason = review?.reason ?: "not_assigned".takeIf { review == null },
				reclaimCandidate = review?.candidate == true, canReclaim = reclaim.canReclaim, reclaimReason = reclaim.reason, reclaimMethod = reclaim.method,
				lastControl = assessment.ledger.lastControls[seat.id]?.let { SeatControlRef(it.operationId.toString(), it.kind) },
			)
		}.sortedWith(compareBy<MemberSeat> { if (it.state == "released") 1 else 0 }.thenBy { it.vendorName }.thenBy { it.account })
		return MemberSeatsResponse(CurrentMeta(organization.id.toString(), now.toString(), token.asOf.toString(), token.value, AnalyticsFrames.USD, QueryReader.SEOUL_ID),
			memberId.toString(), policy(organization), items)
	}

	/**
	 * 등록 제품 하나의 좌석 (`GET O/vendors/{vendorId}/seats`). 현재 상태라 토큰의 기준 시각으로 원장을 다시 세운다. 그 조직에 없는 제품(보관 포함)은 404.
	 * 계정 오름차순 + 좌석 ID, cursor 는 그 토큰·이 제품의 것이어야 한다.
	 */
	fun vendorSeats(organization: Organization, vendorId: String, page: PageRequest, snapshotId: String?): VendorSeatsResponse {
		val now = clock.instant()
		val token = tokens.resolve(VENDOR_SEATS_KIND, organization.id, snapshotId ?: page.cursor?.snapshotId, now)
		val scope = "$VENDOR_SEATS_KIND:$vendorId"
		page.cursor?.let { if (it.snapshotId != token.value || it.scope != scope) throw DashboardException.invalid(CURSOR, FieldErrorCode.INVALID_CURSOR) }
		val roster = seats.roster(organization.id, token.asOf)
		val assessment = seats.assess(organization.id, token.asOf, roster.keys, withReviews = false)
		val state = assessment.products.firstOrNull { it.product.vendorId == vendorId }
			?: throw DashboardException(com.team376.pulsemetry.dashboard.error.ErrorCode.NOT_FOUND)
		val all = assessment.ledger.seats.filter { it.vendorId == vendorId }.sortedWith(compareBy({ it.account }, { it.id.toString() }))
		val start = page.cursor?.let { cursor ->
			val index = all.indexOfFirst { it.id.toString() == cursor.after.lastOrNull() }
			if (index < 0) throw DashboardException.invalid(CURSOR, FieldErrorCode.INVALID_CURSOR)
			index + 1
		} ?: 0
		val items = all.drop(start).take(page.limit)
		val next = if (start + items.size < all.size) codec.encode(PageCursor(token.value, scope, listOf(items.last().account, items.last().id.toString()))) else null
		return VendorSeatsResponse(CurrentMeta(organization.id.toString(), now.toString(), token.asOf.toString(), token.value, AnalyticsFrames.USD, QueryReader.SEOUL_ID),
			vendorId, state.availability, state.reason, Page(items.map { seat ->
				val reclaim = assessment.reclaim(seat, managementEnabled)
				VendorSeatItem(seat.id.toString(), seat.version, seat.account, seat.accountKind.wire, seat.state.wire, seat.source.wire, seat.memberId?.toString(),
					seat.memberId?.let { roster[it]?.account }, seat.memberLink?.wire, seat.tierId, state.product.tier(seat.tierId)?.label, seat.vendorTier,
					seat.assignedAt.toString(), seat.releaseEffectiveOn?.toString(), seat.releasedAt?.toString(), seat.note, reclaim.canReclaim, reclaim.reason, reclaim.method,
					assessment.ledger.lastControls[seat.id]?.let { SeatControlRef(it.operationId.toString(), it.kind) })
			}, all.size, next))
	}

	/** 회수 후보 한 페이지. cursor 는 그 토큰·이 목록의 것이어야 하고, 뒤따르는 위치는 (유휴 일수, 좌석 ID)다. */
	private fun candidatePage(assessment: SeatAssessment, members: Map<UUID, Pair<String, TeamRef>>, page: PageRequest,
		token: CurrentStateTokens.Token): Section<Page<ReclaimCandidate>> {
		page.cursor?.let { if (it.snapshotId != token.value || it.scope != RECLAIM_SCOPE) throw DashboardException.invalid(CURSOR, FieldErrorCode.INVALID_CURSOR) }
		val (availability, reason) = assessment.candidateSection()
		if (availability == Availability.UNAVAILABLE) {
			if (page.cursor != null) throw DashboardException.invalid(CURSOR, FieldErrorCode.INVALID_CURSOR)
			return Section(availability, reason, null)
		}
		val all = assessment.candidates.filter { it.seat.memberId in members }
		val start = page.cursor?.let { cursor ->
			val index = all.indexOfFirst { it.seat.id.toString() == cursor.after.lastOrNull() }
			if (index < 0) throw DashboardException.invalid(CURSOR, FieldErrorCode.INVALID_CURSOR)
			index + 1
		} ?: 0
		val items = all.drop(start).take(page.limit)
		val next = if (start + items.size < all.size) codec.encode(PageCursor(token.value, RECLAIM_SCOPE, listOf(items.last().idleDays.toString(), items.last().seat.id.toString()))) else null
		return Section(availability, reason, Page(items.map { review ->
			val (account, team) = members.getValue(review.seat.memberId!!)
			val reclaim = assessment.reclaim(review.seat, managementEnabled)
			ReclaimCandidate(review.seat.id.toString(), review.seat.memberId.toString(), account, team, review.seat.vendorId, review.seat.tierId, review.seat.version,
				review.lastUsedAt?.toString(), review.idleDays!!, null, reclaim.canReclaim, reclaim.reason, review.seat.account, reclaim.method)
		}, all.size, next))
	}

	/**
	 * 구성원 화면의 좌석 요약 — 쓸 수 있는 원장(가용성이 unavailable 이 아닌 제품)만 센다. 계약 좌석·미배정은 유효한 계약이 있는 제품 범위에서만 셈한다
	 * (계약 좌석에서 사람 수를 빼지 않는다). 기간 중 사용·비활성은 모든 보유 좌석이 구성원에 이어지고 관측 가능한 제품일 때만 낸다(아니면 null).
	 * 비활성은 기간 전체가 완전하고 그 좌석의 구성원이 기간 내내 설치를 갖고 있을 때만 센다. 절감액은 계약의 해지·감액 조건 원천이 없어 null 이다.
	 */
	private fun seatSummary(view: RosterView): Section<SeatSummary> {
		val assessment = view.seats!!
		val (availability, reason) = assessment.section()
		if (availability == Availability.UNAVAILABLE) return Section(availability, reason, null)
		val usable = assessment.products.filter { it.availability != Availability.UNAVAILABLE }.map { it.product }
		val contracted = usable.filter { it.contractStatus == com.team376.pulsemetry.persistence.enrollment.management.ContractStatus.active && it.contract != null }
		val heldBy = assessment.held.groupingBy { it.vendorId }.eachCount()
		val frame = view.frame
		val kindOf = { vendorId: String -> assessment.ledger.product(vendorId)?.kind }
		val decidable = !frame.empty && assessment.held.all { it.memberId != null && kindOf(it.vendorId) in assessment.ledger.observableKinds }
		val usage = if (decidable) aggregator.totals(frame.snapshot, Side.CURRENT, Axis.MEMBER_PRODUCT, products = references.products(frame.snapshot)) else emptyMap()
		val used = { seat: SeatLedgerReader.SeatAt -> usage[listOf(seat.memberId.toString(), kindOf(seat.vendorId))]?.hasUsage == true }
		val dates = frame.period.current.dates()
		val from = dates.min().atStartOfDay(QueryReader.SEOUL).toInstant()
		val until = dates.max().plusDays(1).atStartOfDay(QueryReader.SEOUL).toInstant()
		val idle = assessment.held.filterNot(used)
		val inactive = if (decidable && frame.currentComplete && idle.all { assessment.observedBetween(it.memberId!!, from, until) }) idle.size.toLong() else null
		val (candidateAvailability, _) = assessment.candidateSection()
		return Section(availability, reason, SeatSummary(
			contracted = contracted.sumOf { p -> p.contract!!.tiers.sumOf { it.seats } },
			assigned = assessment.held.size.toLong(),
			unallocated = contracted.sumOf { p -> maxOf(p.contract!!.tiers.sumOf { it.seats } - (heldBy[p.vendorId] ?: 0), 0L) },
			activeInPeriod = if (decidable) assessment.held.count(used).toLong() else null,
			inactiveAssigned = inactive,
			reclaimCandidates = if (candidateAvailability == Availability.UNAVAILABLE) null else assessment.candidates.size.toLong(),
			estimatedMonthlySavingsUsd = null,
		))
	}

	private fun teamRef(teamIds: List<UUID>, names: Map<UUID, String>): TeamRef = when (teamIds.size) {
		0 -> TeamRef(null, TeamsService.UNASSIGNED_NAME)
		1 -> teamIds.single().let { TeamRef(it.toString(), names[it] ?: it.toString()) }
		else -> TeamRef(null, teamIds.map { names[it] ?: it.toString() }.sorted().joinToString(", "))
	}

	/** 조직의 회수 기준과 그 판(ADR 0046). 저장하지 않은 조직은 서버 기본 설정과 판 0 이다. */
	private fun policy(organization: Organization): IdlePolicy = policies.of(organization.id).let { IdlePolicy(it.reclaimIdleDays, it.version) }

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
		/** 좌석 판정(ADR 0048). 좌석 상태만 쓰는 목록은 회수 검토 없이 만든다. */
		val seats: SeatAssessment?,
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
			plannedVendorIds = roster.plannedVendorIds,
				periodUsage = if (frame.empty || !totals.hasUsage) null else Usage.of(totals, frame.pricingMixed),
				lastUsedAt = lastUsed[roster.id.toString()]?.toString(),
				observation = if (frame.currentCoverage.observedDays > 0) PARTIAL else UNOBSERVED,
				seatState = seats?.seatState(roster.id) ?: SEAT_UNKNOWN,
			)
		}

		fun currentTeam(roster: SnapshotReferences.RosterMember): TeamRef = when (roster.currentTeamIds.size) {
			0 -> TeamRef(null, TeamsService.UNASSIGNED_NAME)
			1 -> roster.currentTeamIds.single().let { TeamRef(it.toString(), teams[it]?.name ?: it.toString()) }
			else -> {
				log.info("구성원 {} 의 현재 소속이 {}개 — 하나를 고르지 않는다", roster.id, roster.currentTeamIds.size)
				TeamRef(null, roster.currentTeamIds.map { teams[it]?.name ?: it.toString() }.sorted().joinToString(", "))
			}
		}
	}

	private fun view(frame: AnalyticsFrames.Frame, assessment: SeatAssessment?): RosterView {
		val snapshot = frame.snapshot
		val teamTotals = if (frame.empty) emptyMap() else aggregator.totals(snapshot, Side.CURRENT, Axis.TEAM)
		val roster = references.roster(snapshot).filter { it.status in ROSTER_STATUSES }
		return RosterView(
			frame = frame,
			roster = roster,
			usage = if (frame.empty) emptyMap() else aggregator.totals(snapshot, Side.CURRENT, Axis.MEMBER),
			lastUsed = references.lastUsed(snapshot),
			teams = references.teams(snapshot).associateBy { it.id },
			organization = aggregator.totals(snapshot, Side.CURRENT, Axis.ORGANIZATION).getValue(emptyList()),
			unassignedUsage = teamTotals[listOf(null)] ?: UsageTotals.EMPTY,
			seats = assessment ?: seats.assess(frame.organization.id, frame.snapshot.asOf, roster.map { it.id }.toSet(), withReviews = false),
		)
	}

	companion object {
		const val DASHBOARD_LIMIT = 20
		const val RECLAIM_KIND = "seat-reclaim-candidates"
		const val MEMBER_SEATS_KIND = "member-seats"
		const val VENDOR_SEATS_KIND = "vendor-seats"
		private const val RECLAIM_SCOPE = "seat-reclaim-candidates"
		private const val UNASSIGNED_SCOPE = "members-unassigned"
		private const val CURSOR = "cursor"
		private const val PARTIAL = "partial"
		private const val UNOBSERVED = "unobserved"
		private const val SEAT_UNKNOWN = "unknown"
		private val ROSTER_STATUSES = setOf("active", "suspended")

		/** 쓰기 API 가 없다 — 초대·배정·회수·복원 모두 비활성. */
	}
}
