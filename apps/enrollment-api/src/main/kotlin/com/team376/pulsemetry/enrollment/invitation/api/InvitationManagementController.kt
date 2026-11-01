package com.team376.pulsemetry.enrollment.invitation.api

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
import com.team376.pulsemetry.enrollment.invitation.application.InvitationInput
import com.team376.pulsemetry.enrollment.management.CommandValues.text
import com.team376.pulsemetry.enrollment.management.CommandValues.long
import com.team376.pulsemetry.enrollment.management.CommandValues.array
import com.team376.pulsemetry.enrollment.management.CommandRules.uuid
import com.team376.pulsemetry.enrollment.management.CommandValues.plannedVendors
import com.team376.pulsemetry.enrollment.management.*
import com.team376.pulsemetry.enrollment.management.ManagementHttp.Response
import com.team376.pulsemetry.enrollment.invitation.application.InvitationCommands

@ManagementApi
@RestController
@ConditionalOnProperty(prefix = "pulsemetry.management", name = ["enabled"], havingValue = "true")
@RequestMapping("/api/v1/organizations/{organizationId}")
class InvitationManagementController(private val http: ManagementHttp, private val commands: InvitationCommands) {
    @PostMapping("/invitations/batch")
    fun `invite`(@PathVariable organizationId: UUID, @RequestBody(required = false) body: JsonNode?, request: HttpServletRequest): ResponseEntity<*> =
        http.command(organizationId, body, request, Response.OK) { actor, input, version, now ->
            commands.invite(organizationId, actor, array(input, "invitations").map { item ->
                InvitationInput(text(item, "email", 320).lowercase(java.util.Locale.ROOT), text(item, "role", 20), plannedVendors(item),
                    item.path("teamId").takeUnless { it.isNull || it.isMissingNode }?.let { uuid(it.asString()) })
            }, now)
        }

    @PostMapping("/invitations/{invitationId}/revoke")
    fun `revoke`(@PathVariable organizationId: UUID, @PathVariable invitationId: String, @RequestBody(required = false) body: JsonNode?, request: HttpServletRequest): ResponseEntity<*> =
        http.command(organizationId, body, request, Response.EMPTY) { actor, input, version, now ->
            commands.revoke(organizationId, invitationId, now)
        }

    @PostMapping("/invitations/{invitationId}/reissue")
    fun `reissue`(@PathVariable organizationId: UUID, @PathVariable invitationId: String, @RequestBody(required = false) body: JsonNode?, request: HttpServletRequest): ResponseEntity<*> =
        http.command(organizationId, body, request, Response.OK) { actor, input, version, now ->
            commands.reissue(organizationId, actor, invitationId, now)
        }

    @PostMapping("/members/{memberId}/installation-invitations")
    fun `installation`(@PathVariable organizationId: UUID, @PathVariable memberId: String, @RequestBody(required = false) body: JsonNode?, request: HttpServletRequest): ResponseEntity<*> =
        http.command(organizationId, body, request, Response.OK) { actor, input, version, now ->
            commands.installationInvitation(organizationId, actor, memberId, long(input, "expectedVersion"), now)
        }

}
