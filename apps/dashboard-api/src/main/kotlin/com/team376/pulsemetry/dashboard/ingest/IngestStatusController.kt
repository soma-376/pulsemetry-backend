package com.team376.pulsemetry.dashboard.ingest

import com.team376.pulsemetry.dashboard.analytics.*

import com.team376.pulsemetry.dashboard.analytics.AnalyticsFrames
import com.team376.pulsemetry.dashboard.authentication.DashboardPrincipal
import com.team376.pulsemetry.dashboard.authorization.DashboardAction
import com.team376.pulsemetry.dashboard.authorization.OrganizationAccess
import com.team376.pulsemetry.dashboard.config.DashboardApiProperties
import com.team376.pulsemetry.dashboard.error.DashboardException
import com.team376.pulsemetry.dashboard.error.FieldErrorCode
import com.team376.pulsemetry.dashboard.request.PageCursorCodec
import com.team376.pulsemetry.dashboard.request.QueryReader
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant

/** 공통 헤더의 조직 수집 현황. 기간·계약·manifest 없이 분석 응답의 `ingest` 조각과 같은 판정을 쓴다(ADR 0041). */
@RestController
class IngestStatusController(
    private val access: OrganizationAccess,
    private val frames: AnalyticsFrames,
    private val clock: Clock,
    private val losses: TransmissionLossReader,
    private val codec: PageCursorCodec,
    private val properties: DashboardApiProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    @GetMapping("/api/v1/organizations/{organizationId}/ingest-status")
    fun status(@AuthenticationPrincipal principal: DashboardPrincipal, @PathVariable organizationId: String): IngestStatusResponse {
        val organization = access.require(principal, organizationId, DashboardAction.ORGANIZATION_SETTINGS)
        val now = clock.instant()
        val loss = losses.summary(organization.id, now)
        // 기존 수집 상태의 원천 장애가 독립적인 RDS 손실 집계를 가리지 않게 한다.
        val status = try { frames.status(organization, now) } catch (e: RuntimeException) {
            log.warn("현재 수집 상태 조회 실패 — 손실 집계는 보존한다", e)
            return IngestStatusResponse(organization.id.toString(), "unknown", "source_query_failed", now.toString(), null,
                properties.ingest.window.toMinutes().toInt(), null, null, null, null, null, null, loss)
        }
        val ingest = status.ingest
        return IngestStatusResponse(organization.id.toString(), ingest.status, ingest.reason, ingest.asOf, ingest.lastReceivedAt,
            ingest.windowMinutes, ingest.activeInstallations, ingest.observedMembers, ingest.eligibleMembers, ingest.coverageRatio,
            status.coverageTargetMembers, status.coverageObservedMembers, loss)
    }

    @GetMapping("/api/v1/organizations/{organizationId}/ingest-status/loss-installations")
    fun installations(@AuthenticationPrincipal principal: DashboardPrincipal, @PathVariable organizationId: String,
        request: HttpServletRequest): LossInstallationsResponse {
        val organization = access.require(principal, organizationId, DashboardAction.ORGANIZATION_SETTINGS)
        val value = request.getParameter("windowEnd") ?: throw DashboardException.invalid("windowEnd", FieldErrorCode.REQUIRED)
        val end = runCatching { Instant.parse(value) }.getOrNull() ?: throw DashboardException.invalid("windowEnd", FieldErrorCode.INVALID_FORMAT)
        val page = QueryReader(request::getParameter).read { page(default = 20, max = 100, codec = codec) }
        return losses.installations(organization.id, end, page, request.getParameter("snapshotId"), clock.instant())
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
    val transmissionLoss: TransmissionLoss,
)
