package com.team376.pulsemetry.dashboard.api

import com.team376.pulsemetry.dashboard.alert.AlertResponse
import com.team376.pulsemetry.dashboard.alert.AlertService
import com.team376.pulsemetry.dashboard.alert.AlertsResponse
import com.team376.pulsemetry.dashboard.authentication.DashboardPrincipal
import com.team376.pulsemetry.dashboard.authorization.DashboardAction
import com.team376.pulsemetry.dashboard.authorization.OrganizationAccess
import com.team376.pulsemetry.dashboard.request.PageCursorCodec
import com.team376.pulsemetry.dashboard.request.QueryReader
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/**
 * 알림 목록·단건 (ADR 0051 §6). 개요의 `alerts` 와 같은 계산이다. 받는 파라미터: `status`(`unacknowledged` 기본·`acknowledged`·`all`),
 * `category`(`security`·`cost`), `limit`(기본 20, 최대 100)·`cursor`·`snapshotId`(현재 상태 토큰). 확인은 enrollment-api 의 명령이다.
 */
@RestController
class AlertController(
	private val access: OrganizationAccess,
	private val alerts: AlertService,
	private val codec: PageCursorCodec,
) {
	@GetMapping("/api/v1/organizations/{organizationId}/alerts")
	fun list(@AuthenticationPrincipal principal: DashboardPrincipal, @PathVariable organizationId: String, request: HttpServletRequest): AlertsResponse {
		val organization = access.require(principal, organizationId, DashboardAction.ORGANIZATION_ANALYTICS)
		val reader = QueryReader(request::getParameter)
		val status = reader.read { choice(STATUS, AlertService.Status.BY_WIRE, AlertService.Status.UNACKNOWLEDGED) }
		val category = if (request.getParameter(CATEGORY) == null) null else reader.read { choice(CATEGORY, AlertService.CATEGORIES.associateWith { it }, null) }
		val page = reader.read { page(default = 20, max = 100, codec = codec) }
		return alerts.list(organization, status, category, page, request.getParameter(SNAPSHOT_ID))
	}

	@GetMapping("/api/v1/organizations/{organizationId}/alerts/{alertId}")
	fun detail(@AuthenticationPrincipal principal: DashboardPrincipal, @PathVariable organizationId: String, @PathVariable alertId: String): AlertResponse =
		alerts.detail(access.require(principal, organizationId, DashboardAction.ORGANIZATION_ANALYTICS), alertId)

	private companion object {
		const val STATUS = "status"
		const val CATEGORY = "category"
		const val SNAPSHOT_ID = "snapshotId"
	}
}
