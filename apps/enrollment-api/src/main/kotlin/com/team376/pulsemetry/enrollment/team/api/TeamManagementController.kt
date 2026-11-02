package com.team376.pulsemetry.enrollment.team.api

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
import com.team376.pulsemetry.enrollment.team.application.TeamChange
import com.team376.pulsemetry.enrollment.team.application.TeamAssignment
import com.team376.pulsemetry.enrollment.management.CommandValues.text
import com.team376.pulsemetry.enrollment.management.CommandValues.long
import com.team376.pulsemetry.enrollment.management.CommandValues.array
import com.team376.pulsemetry.enrollment.management.CommandRules.uuid
import com.team376.pulsemetry.enrollment.management.*
import com.team376.pulsemetry.enrollment.management.ManagementHttp.Response
import com.team376.pulsemetry.enrollment.team.application.TeamCommands

@ManagementApi
@RestController
@ConditionalOnProperty(prefix = "pulsemetry.management", name = ["enabled"], havingValue = "true")
@RequestMapping("/api/v1/organizations/{organizationId}")
class TeamManagementController(private val http: ManagementHttp, private val commands: TeamCommands) {
    @PostMapping("/teams")
    fun `create`(@PathVariable organizationId: UUID, @RequestBody(required = false) body: JsonNode?, request: HttpServletRequest): ResponseEntity<*> =
        http.command(organizationId, body, request, Response.CREATED_TEAM) { actor, input, version, now ->
            commands.createTeam(organizationId, TeamChange(text(input, "teamName", 100)), now)
        }

    @PatchMapping("/teams/{teamId}")
    fun `edit`(@PathVariable organizationId: UUID, @PathVariable teamId: String, @RequestBody(required = false) body: JsonNode?, request: HttpServletRequest): ResponseEntity<*> =
        http.command(organizationId, body, request, Response.OK) { actor, input, version, now ->
            commands.editTeam(organizationId, teamId, TeamChange(text(input, "teamName", 100), long(input, "expectedVersion")), now)
        }

    @DeleteMapping("/teams/{teamId}")
    fun `archive`(@PathVariable organizationId: UUID, @PathVariable teamId: String, @RequestBody(required = false) body: JsonNode?, request: HttpServletRequest): ResponseEntity<*> =
        http.command(organizationId, body, request, Response.EMPTY) { actor, input, version, now ->
            commands.archiveTeam(organizationId, teamId, version, now)
        }

    @PostMapping("/member-team-assignments")
    fun `assign`(@PathVariable organizationId: UUID, @RequestBody(required = false) body: JsonNode?, request: HttpServletRequest): ResponseEntity<*> =
        http.command(organizationId, body, request, Response.OK) { actor, input, version, now ->
            commands.assignTeams(organizationId, array(input, "assignments").map { item ->
                TeamAssignment(uuid(text(item, "memberId", 36)), item.path("teamId").takeUnless { it.isNull || it.isMissingNode }?.let { uuid(it.asString()) }, long(item, "expectedVersion"))
            }, now)
        }

}
