package com.team376.pulsemetry.enrollment.vendor.api

import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.PutMapping
import tools.jackson.databind.JsonNode
import java.util.UUID

import org.springframework.web.bind.annotation.*
import com.team376.pulsemetry.enrollment.management.*
import com.team376.pulsemetry.enrollment.management.ManagementHttp.Response
import com.team376.pulsemetry.enrollment.vendor.application.VendorCommands

@ManagementApi
@RestController
@ConditionalOnProperty(prefix = "pulsemetry.management", name = ["enabled"], havingValue = "true")
@RequestMapping("/api/v1/organizations/{organizationId}")
class VendorManagementController(private val http: ManagementHttp, private val commands: VendorCommands) {
    @PostMapping("/vendors")
    fun `create`(@PathVariable organizationId: UUID, @RequestBody(required = false) body: JsonNode?, request: HttpServletRequest): ResponseEntity<*> =
        http.command(organizationId, body, request, Response.CREATED_VENDOR) { actor, input, version, now ->
            commands.saveVendor(organizationId, actor, null, VendorRequest(input), now)
        }

    @PutMapping("/vendors/{vendorId}/contract")
    fun `contract`(@PathVariable organizationId: UUID, @PathVariable vendorId: String, @RequestBody(required = false) body: JsonNode?, request: HttpServletRequest): ResponseEntity<*> =
        http.command(organizationId, body, request, Response.VENDOR) { actor, input, version, now ->
            commands.saveVendor(organizationId, actor, vendorId, VendorRequest(input), now)
        }

    @PatchMapping("/vendors/{vendorId}")
    fun `rename`(@PathVariable organizationId: UUID, @PathVariable vendorId: String, @RequestBody(required = false) body: JsonNode?, request: HttpServletRequest): ResponseEntity<*> =
        http.command(organizationId, body, request, Response.VENDOR) { actor, input, version, now ->
            commands.renameVendor(organizationId, actor, vendorId, VendorRequest(input), now)
        }

    @DeleteMapping("/vendors/{vendorId}")
    fun `archive`(@PathVariable organizationId: UUID, @PathVariable vendorId: String, @RequestBody(required = false) body: JsonNode?, request: HttpServletRequest): ResponseEntity<*> =
        http.command(organizationId, body, request, Response.EMPTY) { actor, input, version, now ->
            commands.removeVendor(organizationId, actor, vendorId, false, version, now)
        }

    @DeleteMapping("/vendors/{vendorId}/contract")
    fun `removeContract`(@PathVariable organizationId: UUID, @PathVariable vendorId: String, @RequestBody(required = false) body: JsonNode?, request: HttpServletRequest): ResponseEntity<*> =
        http.command(organizationId, body, request, Response.EMPTY) { actor, input, version, now ->
            commands.removeVendor(organizationId, actor, vendorId, true, version, now)
        }

}
