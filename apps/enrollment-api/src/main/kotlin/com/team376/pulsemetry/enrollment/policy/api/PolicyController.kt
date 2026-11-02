package com.team376.pulsemetry.enrollment.policy.api

import com.team376.pulsemetry.domain.enrollment.command.ManagementException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import tools.jackson.databind.JsonNode
import java.util.UUID

import com.team376.pulsemetry.enrollment.management.*
import com.team376.pulsemetry.persistence.enrollment.management.OnboardingStore
import com.team376.pulsemetry.persistence.enrollment.management.OrganizationPolicySettings
import com.team376.pulsemetry.enrollment.policy.application.PolicyChange
import com.team376.pulsemetry.enrollment.policy.application.PolicyCommands
import org.springframework.web.bind.annotation.PostMapping

@ManagementApi
@RestController
@ConditionalOnProperty(prefix = "pulsemetry.management", name = ["enabled"], havingValue = "true")
@RequestMapping("/api/v1/organizations/{organizationId}")
class PolicyController(private val http: ManagementHttp, private val store: OnboardingStore, private val commands: PolicyCommands) {
    @PutMapping("/collection-policy")
    fun save(@PathVariable organizationId: UUID, @RequestBody body: JsonNode, request: HttpServletRequest) =
        http.command(organizationId, body, request, ManagementHttp.Response.OK) { actor, input, _, now -> commands.save(organizationId, actor, parse(input), now) }
    @PostMapping("/onboarding/complete")
    fun complete(@PathVariable organizationId: UUID, @RequestBody(required = false) body: JsonNode?, request: HttpServletRequest) =
        http.command(organizationId, body, request, ManagementHttp.Response.OK) { actor, _, _, now -> commands.complete(organizationId, actor, now) }
    @GetMapping("/onboarding")
    fun state(@PathVariable organizationId: UUID, request: HttpServletRequest): ResponseEntity<JsonNode> {
        http.authorize(organizationId, request)
        return http.read(store.state(organizationId))
    }

    private fun parse(body: JsonNode): PolicyChange {
        val choice = body.path("collectRawContent")
        val expected = body.path("expectedVersion")
        val changesChoice = !choice.isMissingNode
        val changesReclaim = body.has("reclaimIdleDays")
        val changesRetention = body.has("aggregateRetentionMonths")
        if ((changesChoice && !choice.isBoolean) || !expected.isIntegralNumber || !expected.canConvertToLong()) throw ManagementException("invalid_request", 400)
        if (!changesChoice && !changesReclaim && !changesRetention) throw ManagementException("invalid_request", 400)
        val reclaim = if (changesReclaim) option(body.path("reclaimIdleDays"), OrganizationPolicySettings.RECLAIM_IDLE_DAYS, false, "reclaimIdleDays") else null
        val retention = if (changesRetention) option(body.path("aggregateRetentionMonths"), OrganizationPolicySettings.AGGREGATE_RETENTION_MONTHS, true, "aggregateRetentionMonths") else null
        val expectedSettings = body.path("expectedSettingsVersion")
        if ((changesReclaim || changesRetention) && (!expectedSettings.isIntegralNumber || !expectedSettings.canConvertToLong() || expectedSettings.asLong() < 0))
            throw ManagementException("invalid_request", 400, "expectedSettingsVersion")
        return PolicyChange(expected.asLong(), if (changesChoice) choice.booleanValue() else null,
            changesReclaim, reclaim, changesRetention, retention, expectedSettings.takeIf { it.isIntegralNumber }?.asLong())
    }

    private fun option(value: JsonNode, allowed: List<Int>, nullable: Boolean, field: String): Int? {
        if (nullable && value.isNull) return null
        if (!value.isIntegralNumber || !value.canConvertToInt() || value.asInt() !in allowed) throw ManagementException("invalid_request", 400, field)
        return value.asInt()
    }
}
