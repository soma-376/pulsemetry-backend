package com.team376.pulsemetry.dashboard.api

import com.team376.pulsemetry.dashboard.analytics.MemberListResponse
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

	private companion object {
		const val SNAPSHOT_ID = "snapshotId"
	}
}
