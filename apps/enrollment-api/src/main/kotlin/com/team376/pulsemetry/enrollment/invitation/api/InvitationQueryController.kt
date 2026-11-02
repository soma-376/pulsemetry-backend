package com.team376.pulsemetry.enrollment.invitation.api

import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.util.UUID

import com.team376.pulsemetry.enrollment.management.*
import com.team376.pulsemetry.persistence.enrollment.management.OnboardingStore

@ManagementApi
@RestController
@ConditionalOnProperty(prefix = "pulsemetry.management", name = ["enabled"], havingValue = "true")
@RequestMapping("/api/v1/organizations/{organizationId}")
class InvitationQueryController(private val http: ManagementHttp, private val store: OnboardingStore, private val clock: Clock) {
    @GetMapping("/invitations")
    fun list(@PathVariable organizationId: UUID, @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) cursor: String?, @RequestParam(required = false) status: String?,
        @RequestParam(required = false) memberStatus: String?, request: HttpServletRequest): ResponseEntity<JsonNode> {
        http.authorize(organizationId, request)
        return http.read(store.invitations(organizationId, limit, cursor?.let(CommandRules::uuid), status, memberStatus, clock.instant()))
    }
}
