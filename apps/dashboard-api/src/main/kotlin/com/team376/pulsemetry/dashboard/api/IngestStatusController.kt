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

/** 공통 헤더의 조직 수집 현황. 기간·계약·manifest 없이 분석 응답의 `ingest` 조각과 같은 판정을 쓴다(ADR 0041). */
@RestController
class IngestStatusController(
    private val access: OrganizationAccess,
    private val frames: AnalyticsFrames,
    private val clock: Clock,
) {
    @GetMapping("/api/v1/organizations/{organizationId}/ingest-status")
    fun status(@AuthenticationPrincipal principal: DashboardPrincipal, @PathVariable organizationId: String): IngestStatusResponse {
        val organization = access.require(principal, organizationId, DashboardAction.ORGANIZATION_SETTINGS)
        val status = frames.status(organization, clock.instant())
        val ingest = status.ingest
        return IngestStatusResponse(organization.id.toString(), ingest.status, ingest.reason, ingest.asOf, ingest.lastReceivedAt,
            ingest.windowMinutes, ingest.activeInstallations, ingest.observedMembers, ingest.eligibleMembers, ingest.coverageRatio,
            status.coverageTargetMembers, status.coverageObservedMembers)
    }
}

/**
 * 앞의 다섯 필드가 처음 계약이고 나머지는 더한 것이다. `windowMinutes`~`coverageRatio` 는 분석 응답의 `ingest` 조각과 같은 값이고,
 * `coverageTargetMembers`·`coverageObservedMembers` 는 `coverageRatio` 의 분모·분자다.
 */
data class IngestStatusResponse(
    val organizationId: String,
    val status: String,
    val reason: String?,
    val asOf: String,
    val lastReceivedAt: String?,
    val windowMinutes: Int,
    val activeInstallations: Long?,
    val observedMembers: Long?,
    val eligibleMembers: Long?,
    val coverageRatio: Double?,
    val coverageTargetMembers: Long?,
    val coverageObservedMembers: Long?,
)
