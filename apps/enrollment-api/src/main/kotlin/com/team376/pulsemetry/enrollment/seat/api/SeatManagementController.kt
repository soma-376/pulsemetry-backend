package com.team376.pulsemetry.enrollment.seat.api

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
import com.team376.pulsemetry.enrollment.management.*
import com.team376.pulsemetry.enrollment.management.ManagementHttp.Response
import com.team376.pulsemetry.enrollment.seat.application.SeatCommands
import com.team376.pulsemetry.enrollment.seat.application.AssignSeat
import com.team376.pulsemetry.enrollment.seat.application.CorrectSeat
import com.team376.pulsemetry.persistence.enrollment.seat.SeatLedger
import com.team376.pulsemetry.persistence.enrollment.seat.SeatControl
import com.team376.pulsemetry.enrollment.management.CommandValues.text
import com.team376.pulsemetry.enrollment.management.CommandValues.long
import com.team376.pulsemetry.enrollment.management.CommandRules.uuid
import com.team376.pulsemetry.enrollment.management.CommandRules.fail
import com.team376.pulsemetry.enrollment.management.CommandValues.optionalText

@ManagementApi
@RestController
@ConditionalOnProperty(prefix = "pulsemetry.management", name = ["enabled"], havingValue = "true")
@RequestMapping("/api/v1/organizations/{organizationId}")
class SeatManagementController(private val http: ManagementHttp, private val commands: SeatCommands) {
    @PostMapping("/vendors/{vendorId}/seats")
    fun `assign`(@PathVariable organizationId: UUID, @PathVariable vendorId: String, @RequestBody(required = false) body: JsonNode?, request: HttpServletRequest): ResponseEntity<*> =
        http.command(organizationId, body, request, Response.ASSIGNED_SEAT) { actor, input, version, now ->
            commands.assignSeat(organizationId, actor, vendorId, AssignSeat(text(input, "account", 320), memberChoice(input),
                optionalText(input, "tierId", 100), optionalText(input, "note", 1000),
                input.path("expectedVersion").takeUnless { it.isMissingNode || it.isNull }?.let { long(input, "expectedVersion") }))
        }

    @PostMapping("/vendors/{vendorId}/seats/import")
    fun `import`(@PathVariable organizationId: UUID, @PathVariable vendorId: String, @RequestBody(required = false) body: JsonNode?, request: HttpServletRequest): ResponseEntity<*> =
        http.command(organizationId, body, request, Response.SEAT) { actor, input, version, now ->
            val mode = text(input, "mode", 10)
            if (mode !in setOf("preview", "apply")) fail("invalid_request", 400, "mode")
            val csv = input.path("csv").takeIf { it.isString }?.asString() ?: fail("invalid_request", 400, "csv")
            commands.importSeats(organizationId, actor, vendorId, csv, mode == "apply")
        }

    @PostMapping("/vendors/{vendorId}/seats/{seatId}/release")
    fun `release`(@PathVariable organizationId: UUID, @PathVariable vendorId: String, @PathVariable seatId: String, @RequestBody(required = false) body: JsonNode?, request: HttpServletRequest): ResponseEntity<*> =
        http.command(organizationId, body, request, Response.SEAT) { actor, input, version, now ->
            commands.releaseSeat(organizationId, actor, vendorId, seatId, long(input, "expectedVersion"))
        }

    @PatchMapping("/vendors/{vendorId}/seats/{seatId}")
    fun `correct`(@PathVariable organizationId: UUID, @PathVariable vendorId: String, @PathVariable seatId: String, @RequestBody(required = false) body: JsonNode?, request: HttpServletRequest): ResponseEntity<*> =
        http.command(organizationId, body, request, Response.SEAT) { actor, input, version, now ->
            val automatic = input.path("memberLink").takeUnless { it.isMissingNode }?.let {
                if (!it.isString || it.asString() != "automatic" || input.has("memberId")) fail("invalid_request", 400, "memberLink")
                SeatLedger.MemberChoice.Automatic
            }
            commands.correctSeat(organizationId, actor, vendorId, seatId, CorrectSeat(long(input, "expectedVersion"), automatic ?: memberChoice(input),
                if (input.has("tierId")) SeatLedger.Change(optionalText(input, "tierId", 100)) else null,
                if (input.has("note")) SeatLedger.Change(optionalText(input, "note", 1000)) else null))
        }

    @PostMapping("/seat-reclaims/preview")
    fun `preview`(@PathVariable organizationId: UUID, @RequestBody(required = false) body: JsonNode?, request: HttpServletRequest): ResponseEntity<*> =
        http.command(organizationId, body, request, Response.OK) { actor, input, version, now ->
            val seats = input.path("seats").takeIf { it.isArray && it.size() in 1..SeatControl.MAX_SEATS }?.toList() ?: fail("invalid_request", 400, "seats")
            commands.previewReclaim(organizationId, actor, seats.map { seat ->
                if (!seat.isObject) fail("invalid_request", 400, "seats")
                text(seat, "seatAssignmentId", 36) to long(seat, "expectedVersion")
            })
        }

    @PostMapping("/seat-reclaims")
    fun `execute`(@PathVariable organizationId: UUID, @RequestBody(required = false) body: JsonNode?, request: HttpServletRequest): ResponseEntity<*> =
        http.command(organizationId, body, request, Response.ACCEPTED) { actor, input, version, now ->
            commands.executeReclaim(organizationId, actor, uuid(text(input, "previewId", 36)))
        }

    @PostMapping("/seat-reclaims/{operationId}/restore")
    fun `restore`(@PathVariable organizationId: UUID, @PathVariable operationId: String, @RequestBody(required = false) body: JsonNode?, request: HttpServletRequest): ResponseEntity<*> =
        http.command(organizationId, body, request, Response.ACCEPTED) { actor, input, version, now ->
            commands.restore(organizationId, actor, operationId)
        }

    @PostMapping("/operations/{operationId}/targets/{targetId}/confirm")
    fun `confirm`(@PathVariable organizationId: UUID, @PathVariable operationId: String, @PathVariable targetId: String, @RequestBody(required = false) body: JsonNode?, request: HttpServletRequest): ResponseEntity<*> =
        http.command(organizationId, body, request, Response.OK) { actor, input, version, now ->
            commands.confirm(organizationId, actor, operationId, targetId)
        }

    @PostMapping("/operations/{operationId}/targets/{targetId}/cancel")
    fun `cancel`(@PathVariable organizationId: UUID, @PathVariable operationId: String, @PathVariable targetId: String, @RequestBody(required = false) body: JsonNode?, request: HttpServletRequest): ResponseEntity<*> =
        http.command(organizationId, body, request, Response.OK) { actor, input, version, now ->
            commands.cancel(organizationId, actor, operationId, targetId)
        }

    @PostMapping("/vendors/{vendorId}/connection/sync")
    fun `sync`(@PathVariable organizationId: UUID, @PathVariable vendorId: String, @RequestBody(required = false) body: JsonNode?, request: HttpServletRequest): ResponseEntity<*> =
        http.command(organizationId, body, request, Response.ACCEPTED) { actor, input, version, now ->
            commands.sync(organizationId, actor, vendorId)
        }

    private fun memberChoice(body: JsonNode): SeatLedger.MemberChoice? {
        if (!body.has("memberId")) return null
        val value = body.path("memberId")
        if (value.isNull) return SeatLedger.MemberChoice.Unlinked
        if (!value.isString) fail("invalid_request", 400, "memberId")
        return SeatLedger.MemberChoice.Member(uuid(value.asString()))
    }
}
