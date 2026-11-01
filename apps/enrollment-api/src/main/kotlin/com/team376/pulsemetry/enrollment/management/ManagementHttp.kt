package com.team376.pulsemetry.enrollment.management

import com.team376.pulsemetry.persistence.enrollment.management.ManagementException
import com.team376.pulsemetry.security.user.UserAuthService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.util.UUID


/** HTTP 표현과 기존 재시도 키를 만든다. 실행할 업무는 기능 API의 명시적 호출로 고정한다. */
class ManagementHttp(private val auth: UserAuthService, private val executor: ManagementExecutor, private val mapper: ObjectMapper) {
    enum class Response { OK, CREATED_TEAM, CREATED_VENDOR, VENDOR, SEAT, ASSIGNED_SEAT, ACCEPTED, EMPTY }
    fun authorize(tenant: UUID, request: HttpServletRequest) = managementActor(auth, tenant, request)
    fun read(result: JsonNode): ResponseEntity<JsonNode> = ResponseEntity.ok().header("Cache-Control", "no-store").body(result)

    fun command(tenant: UUID, body: JsonNode?, request: HttpServletRequest, response: Response,
        work: (UUID, JsonNode, Long?, java.time.Instant) -> Any): ResponseEntity<*> {
        val actor = authorize(tenant, request)
        val version = request.getHeader("If-Match")?.let {
            Regex("\"(?:team|vendor)-([0-9]+)\"").matchEntire(it)?.groupValues?.get(1)?.toLongOrNull()
                ?: throw ManagementException("invalid_request", 400, "If-Match")
        }
        val input = body ?: mapper.createObjectNode()
        // 배포된 management_commands와 AES-GCM AAD의 식별자를 보존한다. 이 값으로 업무를 선택하지 않는다.
        val replayScope = "${request.method} ${request.requestURI.substringAfter("/organizations/$tenant")}"
        val encoded = executor.execute(tenant, actor, replayScope, CommandRules.hash(CommandValues.canonical(input)), request.getHeader("Idempotency-Key"), request.method == "POST") { now ->
            mapper.writeValueAsString(work(actor, input, version, now))
        }
        val result = mapper.readTree(encoded)
        if (response == Response.EMPTY) return ResponseEntity.noContent().build<Void>()
        if (response == Response.ACCEPTED) return ResponseEntity.accepted().header("Cache-Control", "no-store")
            .location(URI("/api/v1/organizations/$tenant/operations/${result.path("operationId").asString()}")).body(result)
        val seat = result.path("seat")
        val status = when (response) {
            Response.CREATED_TEAM, Response.CREATED_VENDOR -> 201
            Response.ASSIGNED_SEAT -> if (seat.path("version").asLong() == 1L) 201 else 200
            else -> 200
        }
        val builder = ResponseEntity.status(status).header("Cache-Control", "no-store")
        if (response in setOf(Response.VENDOR, Response.CREATED_VENDOR)) builder.eTag("vendor-${result.path("vendor").path("version").asLong()}")
        if (response in setOf(Response.SEAT, Response.ASSIGNED_SEAT) && !seat.isMissingNode) builder.eTag("seat-${seat.path("version").asLong()}")
        if (status == 201) {
            val id = when (response) {
                Response.CREATED_TEAM -> result.path("teamId").asString()
                Response.CREATED_VENDOR -> result.path("vendor").path("vendorId").asString()
                else -> seat.path("seatAssignmentId").asString()
            }
            builder.location(URI(request.requestURI + "/" + id))
        }
        return builder.body(result)
    }
}
