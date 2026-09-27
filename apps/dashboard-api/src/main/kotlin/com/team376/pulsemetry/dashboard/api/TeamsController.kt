package com.team376.pulsemetry.dashboard.api

import com.team376.pulsemetry.dashboard.analytics.TeamDetailResponse
import com.team376.pulsemetry.dashboard.analytics.TeamDirectoryResponse
import com.team376.pulsemetry.dashboard.analytics.TeamDirectoryService
import com.team376.pulsemetry.dashboard.analytics.TeamUsersResponse
import com.team376.pulsemetry.dashboard.analytics.TeamsResponse
import com.team376.pulsemetry.dashboard.analytics.TeamsService
import com.team376.pulsemetry.dashboard.analytics.TeamsService.TeamKey
import com.team376.pulsemetry.dashboard.authentication.DashboardPrincipal
import com.team376.pulsemetry.dashboard.authorization.DashboardAction
import com.team376.pulsemetry.dashboard.authorization.OrganizationAccess
import com.team376.pulsemetry.dashboard.error.DashboardException
import com.team376.pulsemetry.dashboard.error.ErrorCode
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
 * 팀 분석 명세의 세 조회와 조직 팀 선택지. 받는 파라미터는 명세의 표 그대로다 — 목록은 기간·비교·`sort`·`limit`(기본 20, 최대 50)·`cursor`·`snapshotId`,
 * 상세는 기간·비교·`snapshotId`, 사용자는 기간·`snapshotId`·`limit`(기본 12, 최대 100)·`cursor`, 선택지는 `q`·`limit`(기본 50, 최대 100)·`cursor`·`snapshotId`.
 */
@RestController
class TeamsController(
	private val access: OrganizationAccess,
	private val teams: TeamsService,
	private val directory: TeamDirectoryService,
	private val codec: PageCursorCodec,
) {

	@GetMapping("/api/v1/organizations/{organizationId}/analytics/teams")
	fun teams(
		@AuthenticationPrincipal principal: DashboardPrincipal,
		@PathVariable organizationId: String,
		request: HttpServletRequest,
	): TeamsResponse {
		val organization = access.require(principal, organizationId, DashboardAction.TEAM_ANALYTICS)
		val (period, sort, page) = QueryReader(request::getParameter).read {
			Triple(comparedPeriod(), choice("sort", TeamsService.Sort.BY_WIRE, TeamsService.Sort.COST), page(default = 20, max = 50, codec = codec))
		}
		return teams.teams(organization, principal.memberId, period, sort, page, request.getParameter(SNAPSHOT_ID))
	}

	@GetMapping("/api/v1/organizations/{organizationId}/analytics/teams/{teamId}")
	fun team(
		@AuthenticationPrincipal principal: DashboardPrincipal,
		@PathVariable organizationId: String,
		@PathVariable teamId: String,
		request: HttpServletRequest,
	): TeamDetailResponse {
		val parsed = TeamKey.parseOrNull(teamId)
		val organization = access.require(principal, organizationId, DashboardAction.TEAM_ANALYTICS, (parsed as? TeamKey.Team)?.id)
		val key = parsed ?: throw DashboardException(ErrorCode.NOT_FOUND)
		val period = QueryReader(request::getParameter).read { comparedPeriod() }
		return teams.team(organization, principal.memberId, key, period, request.getParameter(SNAPSHOT_ID))
	}

	@GetMapping("/api/v1/organizations/{organizationId}/analytics/teams/{teamId}/users")
	fun users(
		@AuthenticationPrincipal principal: DashboardPrincipal,
		@PathVariable organizationId: String,
		@PathVariable teamId: String,
		request: HttpServletRequest,
	): TeamUsersResponse {
		val parsed = TeamKey.parseOrNull(teamId)
		// 개인 사용량이다 — 팀 집계와 다른 행위로 판정한다(개인 목록 권한이 없으면 403).
		val organization = access.require(principal, organizationId, DashboardAction.INDIVIDUAL_USAGE, (parsed as? TeamKey.Team)?.id)
		val key = parsed ?: throw DashboardException(ErrorCode.NOT_FOUND)
		val (period, page) = QueryReader(request::getParameter).read { period() to page(default = 12, max = 100, codec = codec) }
		return teams.users(organization, principal.memberId, key, ComparedPeriod(period, CompareMode.NONE), page, request.getParameter(SNAPSHOT_ID))
	}

	@GetMapping("/api/v1/organizations/{organizationId}/teams")
	fun directory(
		@AuthenticationPrincipal principal: DashboardPrincipal,
		@PathVariable organizationId: String,
		request: HttpServletRequest,
	): TeamDirectoryResponse {
		val organization = access.require(principal, organizationId, DashboardAction.MEMBER_DIRECTORY)
		val (search, page) = QueryReader(request::getParameter).read { search() to page(default = 50, max = 100, codec = codec) }
		return directory.teams(organization, search, page, request.getParameter(SNAPSHOT_ID))
	}

	private companion object {
		const val SNAPSHOT_ID = "snapshotId"
	}
}
