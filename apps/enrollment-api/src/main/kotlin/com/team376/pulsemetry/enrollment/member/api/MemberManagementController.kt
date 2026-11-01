package com.team376.pulsemetry.enrollment.member.api

import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import java.util.UUID

import org.springframework.web.bind.annotation.*
import com.team376.pulsemetry.enrollment.member.application.MemberChange
import com.team376.pulsemetry.enrollment.management.CommandValues.text
import com.team376.pulsemetry.enrollment.management.CommandValues.long
import com.team376.pulsemetry.enrollment.management.CommandRules.uuid
import com.team376.pulsemetry.enrollment.management.CommandValues.plannedVendors
import com.team376.pulsemetry.enrollment.management.CommandRules.fail
import com.team376.pulsemetry.enrollment.management.*
import com.team376.pulsemetry.enrollment.management.ManagementHttp.Response
import com.team376.pulsemetry.enrollment.member.application.MemberCommands

@ManagementApi
@RestController
@ConditionalOnProperty(prefix = "pulsemetry.management", name = ["enabled"], havingValue = "true")
@RequestMapping("/api/v1/organizations/{organizationId}")
class MemberManagementController(private val http: ManagementHttp, private val commands: MemberCommands) {
    @PatchMapping("/members/{memberId}")
    fun `edit`(@PathVariable organizationId: UUID, @PathVariable memberId: String, @RequestBody(required = false) body: JsonNode?, request: HttpServletRequest): ResponseEntity<*> =
        http.command(organizationId, body, request, Response.OK) { actor, input, version, now ->
            commands.editMember(organizationId, actor, memberId, MemberChange(
                long(input, "expectedVersion"), input.has("teamId"),
                input.path("teamId").takeUnless { it.isNull || it.isMissingNode }?.let {
                    if (!it.isString) fail("invalid_request", 400, "teamId")
                    uuid(it.asString())
                }, if (input.has("role")) text(input, "role", 20) else null,
                if (input.has("plannedVendorIds")) plannedVendors(input) else null), now)
        }

}
