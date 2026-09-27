package com.team376.pulsemetry.dashboard.api

import com.team376.pulsemetry.dashboard.analytics.OverviewResponse
import com.team376.pulsemetry.dashboard.analytics.OverviewService
import com.team376.pulsemetry.dashboard.authentication.DashboardPrincipal
import com.team376.pulsemetry.dashboard.authorization.DashboardAction
import com.team376.pulsemetry.dashboard.authorization.OrganizationAccess
import com.team376.pulsemetry.dashboard.request.QueryReader
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/**
 * `GET /api/v1/organizations/{organizationId}/analytics/overview` (개요 명세). 받는 파라미터는 `startDate`·`endDate`·`compare`·`timeZone`.
 * 요청마다 snapshot 을 새로 만들고 그 ID 는 응답에 싣지 않는다.
 */
@RestController
class OverviewController(
	private val access: OrganizationAccess,
	private val overview: OverviewService,
) {

	@GetMapping("/api/v1/organizations/{organizationId}/analytics/overview")
	fun overview(
		@AuthenticationPrincipal principal: DashboardPrincipal,
		@PathVariable organizationId: String,
		request: HttpServletRequest,
	): OverviewResponse {
		val organization = access.require(principal, organizationId, DashboardAction.ORGANIZATION_ANALYTICS)
		val period = QueryReader(request::getParameter).read { comparedPeriod() }
		return overview.overview(organization, principal.memberId, period)
	}
}
