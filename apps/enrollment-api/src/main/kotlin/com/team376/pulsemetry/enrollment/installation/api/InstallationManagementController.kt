package com.team376.pulsemetry.enrollment.installation.api

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
import com.team376.pulsemetry.enrollment.installation.application.InstallationNoticeCommand
import com.team376.pulsemetry.enrollment.management.CommandValues.long
import com.team376.pulsemetry.enrollment.management.CommandValues.array
import com.team376.pulsemetry.enrollment.management.CommandRules.uuid
import com.team376.pulsemetry.enrollment.management.CommandRules.fail
import com.team376.pulsemetry.enrollment.management.*
import com.team376.pulsemetry.enrollment.management.ManagementHttp.Response
import com.team376.pulsemetry.enrollment.installation.application.InstallationCommands

@ManagementApi
@RestController
@ConditionalOnProperty(prefix = "pulsemetry.management", name = ["enabled"], havingValue = "true")
@RequestMapping("/api/v1/organizations/{organizationId}")
class InstallationManagementController(private val http: ManagementHttp, private val commands: InstallationCommands) {
    @PostMapping("/installation-update-notifications")
    fun `notify`(@PathVariable organizationId: UUID, @RequestBody(required = false) body: JsonNode?, request: HttpServletRequest): ResponseEntity<*> =
        http.command(organizationId, body, request, Response.ACCEPTED) { actor, input, version, now ->
            commands.notifyInstallations(organizationId, actor, InstallationNoticeCommand(array(input, "installationIds").map {
                if (!it.isString) fail("invalid_request", 400, "installationIds")
                uuid(it.asString())
            }, long(input, "expectedPolicyVersion")))
        }

}
