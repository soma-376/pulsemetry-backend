package com.team376.pulsemetry.dashboard.api

import com.team376.pulsemetry.dashboard.analytics.InstallationsResponse
import com.team376.pulsemetry.dashboard.analytics.SettingsResponse
import com.team376.pulsemetry.dashboard.analytics.SettingsService
import com.team376.pulsemetry.dashboard.analytics.VendorResponse
import com.team376.pulsemetry.dashboard.analytics.VendorsResponse
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
 * 설정 명세의 조회 넷. 선택 기간이 없다. 받는 파라미터: 벤더 목록 `limit`(기본 20, 최대 100)·`cursor`·`snapshotId`, 벤더 상세 `snapshotId`(선택 — 주면 그 기준 시각의
 * 관측 고정을 쓴다, ADR 0044), 설치 `policyStatus`(`applied`·`outdated`·`unknown`)·`limit`·`cursor`·`snapshotId`.
 */
@RestController
class SettingsController(
	private val access: OrganizationAccess,
	private val settings: SettingsService,
	private val codec: PageCursorCodec,
) {

	@GetMapping("/api/v1/organizations/{organizationId}/settings")
	fun settings(@AuthenticationPrincipal principal: DashboardPrincipal, @PathVariable organizationId: String): SettingsResponse =
		settings.settings(access.require(principal, organizationId, DashboardAction.ORGANIZATION_SETTINGS))

	@GetMapping("/api/v1/organizations/{organizationId}/vendors")
	fun vendors(@AuthenticationPrincipal principal: DashboardPrincipal, @PathVariable organizationId: String, request: HttpServletRequest): VendorsResponse {
		val organization = access.require(principal, organizationId, DashboardAction.ORGANIZATION_SETTINGS)
		val page = QueryReader(request::getParameter).read { page(default = 20, max = 100, codec = codec) }
		return settings.vendors(organization, page, request.getParameter(SNAPSHOT_ID))
	}

	@GetMapping("/api/v1/organizations/{organizationId}/vendors/{vendorId}")
	fun vendor(
		@AuthenticationPrincipal principal: DashboardPrincipal,
		@PathVariable organizationId: String,
		@PathVariable vendorId: String,
		request: HttpServletRequest,
	): VendorResponse = settings.vendor(access.require(principal, organizationId, DashboardAction.ORGANIZATION_SETTINGS), vendorId, request.getParameter(SNAPSHOT_ID))

	@GetMapping("/api/v1/organizations/{organizationId}/installations")
	fun installations(
		@AuthenticationPrincipal principal: DashboardPrincipal,
		@PathVariable organizationId: String,
		request: HttpServletRequest,
	): InstallationsResponse {
		val organization = access.require(principal, organizationId, DashboardAction.ORGANIZATION_SETTINGS)
		val (status, page) = QueryReader(request::getParameter).read {
			(if (request.getParameter(POLICY_STATUS) == null) null else choice(POLICY_STATUS, POLICY_STATUSES, null)) to page(default = 20, max = 100, codec = codec)
		}
		return settings.installations(organization, status, page, request.getParameter(SNAPSHOT_ID))
	}

	private companion object {
		const val SNAPSHOT_ID = "snapshotId"
		const val POLICY_STATUS = "policyStatus"
		val POLICY_STATUSES = SettingsService.PolicyStatus.BY_WIRE
	}
}
