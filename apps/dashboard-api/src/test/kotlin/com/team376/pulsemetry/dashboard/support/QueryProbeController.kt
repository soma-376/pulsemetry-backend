package com.team376.pulsemetry.dashboard.support

import com.team376.pulsemetry.dashboard.authentication.DashboardPrincipal
import com.team376.pulsemetry.dashboard.authorization.DashboardAction
import com.team376.pulsemetry.dashboard.authorization.OrganizationAccess
import com.team376.pulsemetry.dashboard.request.PageCursorCodec
import com.team376.pulsemetry.dashboard.request.QueryReader
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.test.context.TestComponent
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * **테스트 전용** 컨트롤러 — 공통 관문([OrganizationAccess])과 요청 해석([QueryReader])을 실제 필터 체인 뒤에서 부른다.
 * `@TestComponent` 라 스캔되지 않고 [AbstractDashboardApiTest] 가 가져올 때만 선다. 운영 경로가 아니다.
 */
@TestComponent
@RestController
class QueryProbeController(
	private val access: OrganizationAccess,
	private val codec: PageCursorCodec,
) {

	@GetMapping("/api/v1/organizations/{organizationId}/probe/compared")
	fun compared(
		@AuthenticationPrincipal principal: DashboardPrincipal,
		@PathVariable organizationId: String,
		request: HttpServletRequest,
	): Map<String, Any?> {
		val organization = access.require(principal, organizationId, DashboardAction.ORGANIZATION_ANALYTICS)
		val query = QueryReader(request::getParameter).read { comparedPeriod() }
		return mapOf(
			"organizationId" to organization.id.toString(),
			"days" to query.current.days,
			"from" to query.current.from.toString(),
			"until" to query.current.until.toString(),
			"compare" to query.mode.wire,
			"previousStartDate" to query.previous?.startDate?.toString(),
			"previousEndDate" to query.previous?.endDate?.toString(),
		)
	}

	@GetMapping("/api/v1/organizations/{organizationId}/probe/page")
	fun page(
		@AuthenticationPrincipal principal: DashboardPrincipal,
		@PathVariable organizationId: String,
		request: HttpServletRequest,
	): Map<String, Any?> {
		access.require(principal, organizationId, DashboardAction.MEMBER_DIRECTORY)
		val (period, page, search) = QueryReader(request::getParameter).read {
			Triple(period(), page(default = 20, max = 100, codec = codec), search())
		}
		return mapOf(
			"days" to period.days,
			"limit" to page.limit,
			"cursorSnapshotId" to page.cursor?.snapshotId,
			"q" to search,
		)
	}

	@GetMapping("/api/v1/organizations/{organizationId}/probe/action/{action}")
	fun action(
		@AuthenticationPrincipal principal: DashboardPrincipal,
		@PathVariable organizationId: String,
		@PathVariable action: String,
	): Map<String, Any?> {
		val organization = access.require(principal, organizationId, DashboardAction.valueOf(action), teamId = UUID.randomUUID())
		return mapOf("organizationId" to organization.id.toString(), "name" to organization.name)
	}
}
