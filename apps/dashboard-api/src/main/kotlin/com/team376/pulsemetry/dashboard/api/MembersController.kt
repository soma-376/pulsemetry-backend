package com.team376.pulsemetry.dashboard.api

import com.team376.pulsemetry.dashboard.analytics.MemberListResponse
import com.team376.pulsemetry.dashboard.analytics.MemberSeatsResponse
import com.team376.pulsemetry.dashboard.analytics.MembersResponse
import com.team376.pulsemetry.dashboard.analytics.MembersService
import com.team376.pulsemetry.dashboard.analytics.ReclaimCandidatesResponse
import com.team376.pulsemetry.dashboard.authentication.DashboardPrincipal
import com.team376.pulsemetry.dashboard.authorization.DashboardAction
import com.team376.pulsemetry.dashboard.authorization.OrganizationAccess
import com.team376.pulsemetry.dashboard.request.CompareMode
import com.team376.pulsemetry.dashboard.request.ComparedPeriod
import com.team376.pulsemetry.dashboard.request.PageCursorCodec
import com.team376.pulsemetry.dashboard.request.QueryReader
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/**
 * 구성원 명세의 조회 넷. 모두 개인 목록이라 구성원 디렉터리 권한으로 판정한다. 비교는 없다(받지 않는다).
 * `dashboard` 는 기간만, `members` 는 기간·`q`·`limit`(기본 20, 최대 100)·`cursor`·`snapshotId`, `unassigned` 는 기간·`limit`·`cursor`·`snapshotId`,
 * 회수 후보는 `limit`·`cursor`·`snapshotId`.
 */
@RestController
class MembersController(
	private val access: OrganizationAccess,
	private val members: MembersService,
	private val codec: PageCursorCodec,
) {

	@GetMapping("/api/v1/organizations/{organizationId}/members/dashboard")
	fun dashboard(
		@AuthenticationPrincipal principal: DashboardPrincipal,
		@PathVariable organizationId: String,
		request: HttpServletRequest,
	): MembersResponse {
		val organization = access.require(principal, organizationId, DashboardAction.MEMBER_DIRECTORY)
		val period = QueryReader(request::getParameter).read { period() }
		return members.dashboard(organization, principal.memberId, ComparedPeriod(period, CompareMode.NONE))
	}

	@GetMapping("/api/v1/organizations/{organizationId}/members")
	fun members(
		@AuthenticationPrincipal principal: DashboardPrincipal,
		@PathVariable organizationId: String,
		request: HttpServletRequest,
	): MemberListResponse {
		val organization = access.require(principal, organizationId, DashboardAction.MEMBER_DIRECTORY)
		val (period, search, page) = QueryReader(request::getParameter).read { Triple(period(), search(), page(default = 20, max = 100, codec = codec)) }
		return members.members(organization, principal.memberId, ComparedPeriod(period, CompareMode.NONE), search, page, request.getParameter(SNAPSHOT_ID))
	}

	@GetMapping("/api/v1/organizations/{organizationId}/members/unassigned")
	fun unassigned(
		@AuthenticationPrincipal principal: DashboardPrincipal,
		@PathVariable organizationId: String,
		request: HttpServletRequest,
	): MemberListResponse {
		val organization = access.require(principal, organizationId, DashboardAction.MEMBER_DIRECTORY)
		val (period, page) = QueryReader(request::getParameter).read { period() to page(default = 20, max = 100, codec = codec) }
		return members.unassigned(organization, principal.memberId, ComparedPeriod(period, CompareMode.NONE), page, request.getParameter(SNAPSHOT_ID))
	}

	@GetMapping("/api/v1/organizations/{organizationId}/seat-reclaim-candidates")
	fun reclaimCandidates(
		@AuthenticationPrincipal principal: DashboardPrincipal,
		@PathVariable organizationId: String,
		request: HttpServletRequest,
	): ReclaimCandidatesResponse {
		val organization = access.require(principal, organizationId, DashboardAction.MEMBER_DIRECTORY)
		val page = QueryReader(request::getParameter).read { page(default = 20, max = 100, codec = codec) }
		return members.reclaimCandidates(organization, page, request.getParameter(SNAPSHOT_ID))
	}

	/** 구성원의 벤더 좌석(ADR 0048) — 현재 상태라 `snapshotId` 는 현재 상태 토큰이다. 경로의 구성원 ID 가 UUID 가 아니거나 로스터에 없으면 404. */
	@GetMapping("/api/v1/organizations/{organizationId}/members/{memberId}/seats")
	fun memberSeats(
		@AuthenticationPrincipal principal: DashboardPrincipal,
		@PathVariable organizationId: String,
		@PathVariable memberId: String,
		request: HttpServletRequest,
	): MemberSeatsResponse {
		val organization = access.require(principal, organizationId, DashboardAction.MEMBER_DIRECTORY)
		val id = runCatching { java.util.UUID.fromString(memberId) }.getOrNull()?.takeIf { it.toString() == memberId.lowercase() }
			?: throw com.team376.pulsemetry.dashboard.error.DashboardException(com.team376.pulsemetry.dashboard.error.ErrorCode.NOT_FOUND)
		return members.memberSeats(organization, id, request.getParameter(SNAPSHOT_ID))
	}

	private companion object {
		const val SNAPSHOT_ID = "snapshotId"
	}
}
