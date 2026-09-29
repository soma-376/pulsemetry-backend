package com.team376.pulsemetry.dashboard.api

import com.team376.pulsemetry.dashboard.analytics.AnalyticsFrames
import com.team376.pulsemetry.dashboard.authentication.DashboardPrincipal
import com.team376.pulsemetry.dashboard.authorization.DashboardAction
import com.team376.pulsemetry.dashboard.authorization.OrganizationAccess
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

/** 공통 헤더의 조직 수집 현황. 기간·계약·manifest 없이 기존 수신 이력 판정을 재사용한다. */
@RestController
class IngestStatusController(
    private val access: OrganizationAccess,
    private val frames: AnalyticsFrames,
    private val clock: Clock,
) {
    @GetMapping("/api/v1/organizations/{organizationId}/ingest-status")
    fun status(@AuthenticationPrincipal principal: DashboardPrincipal, @PathVariable organizationId: String): IngestStatusResponse {
        val organization = access.require(principal, organizationId, DashboardAction.ORGANIZATION_SETTINGS)
        val ingest = frames.ingest(organization, clock.instant())
        return IngestStatusResponse(organization.id.toString(), ingest.status, ingest.reason, ingest.asOf, ingest.lastReceivedAt)
    }
}

data class IngestStatusResponse(
    val organizationId: String,
    val status: String,
    val reason: String?,
    val asOf: String,
    val lastReceivedAt: String?,
)
