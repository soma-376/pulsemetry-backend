package com.team376.pulsemetry.enrollment.management

import com.team376.pulsemetry.persistence.enrollment.management.ManagementException
import com.team376.pulsemetry.persistence.enrollment.management.ManagementStore
import com.team376.pulsemetry.security.user.UserAuthException
import com.team376.pulsemetry.security.user.UserAuthService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestParam
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.time.Clock
import java.util.UUID

@ConfigurationProperties("pulsemetry.management")
class ManagementProperties {
    var enabled = false
    var responseEncryptionKey = ""
    var onboardingOtlpEndpoint = ""
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pulsemetry.management", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(ManagementProperties::class)
class ManagementConfig {
    @Bean
    fun managementStore(jdbc: JdbcClient, manager: PlatformTransactionManager, mapper: ObjectMapper, clock: Clock,
        properties: ManagementProperties): ManagementStore = ManagementStore(jdbc, manager, mapper, clock,
            properties.responseEncryptionKey) { InitialOnboardingManifest.create(properties.onboardingOtlpEndpoint, mapper) }
}

@RestController
@ConditionalOnProperty(prefix = "pulsemetry.management", name = ["enabled"], havingValue = "true")
@RequestMapping("/api/v1/organizations/{organizationId}")
class ManagementController(private val auth: UserAuthService, private val store: ManagementStore, private val mapper: ObjectMapper) {
    @RequestMapping(path = ["/teams", "/member-team-assignments", "/invitations/batch", "/invitations/{invitationId}/revoke", "/invitations/{invitationId}/reissue", "/vendors", "/onboarding/complete"], method = [RequestMethod.POST])
    fun post(@PathVariable organizationId: UUID, @RequestBody(required = false) body: JsonNode?, request: HttpServletRequest) = handle(organizationId, body, request)

    @RequestMapping(path = ["/teams/{teamId}"], method = [RequestMethod.PATCH, RequestMethod.DELETE])
    fun team(@PathVariable organizationId: UUID, @RequestBody(required = false) body: JsonNode?, request: HttpServletRequest) = handle(organizationId, body, request)

    @RequestMapping(path = ["/members/{memberId}"], method = [RequestMethod.PATCH])
    fun member(@PathVariable organizationId: UUID, @RequestBody(required = false) body: JsonNode?, request: HttpServletRequest) = handle(organizationId, body, request)

    @RequestMapping(path = ["/vendors/{vendorId}/contract"], method = [RequestMethod.PUT, RequestMethod.DELETE])
    fun contract(@PathVariable organizationId: UUID, @RequestBody(required = false) body: JsonNode?, request: HttpServletRequest) = handle(organizationId, body, request)

    @RequestMapping(path = ["/vendors/{vendorId}"], method = [RequestMethod.PATCH, RequestMethod.DELETE])
    fun vendor(@PathVariable organizationId: UUID, @RequestBody(required = false) body: JsonNode?, request: HttpServletRequest) = handle(organizationId, body, request)

    @PutMapping("/collection-policy")
    fun policy(@PathVariable organizationId: UUID, @RequestBody body: JsonNode, request: HttpServletRequest) = handle(organizationId, body, request)

    @GetMapping("/onboarding")
    fun onboarding(@PathVariable organizationId: UUID, request: HttpServletRequest): ResponseEntity<JsonNode> {
        authorize(organizationId, request)
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(store.onboarding(organizationId))
    }

    @GetMapping("/invitations")
    fun invitations(@PathVariable organizationId: UUID, @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) cursor: String?, @RequestParam(required = false) status: String?,
        request: HttpServletRequest): ResponseEntity<JsonNode> {
        authorize(organizationId, request)
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(store.invitations(organizationId, limit, cursor, status))
    }

    private fun authorize(tenant: UUID, request: HttpServletRequest): UUID {
        val header = request.getHeader("Authorization")
        if (header == null || !header.startsWith("Bearer ")) throw ManagementException("unauthenticated", 401)
        val identity = try { auth.verify(header.removePrefix("Bearer ")) } catch (_: UserAuthException) { throw ManagementException("unauthenticated", 401) }
        if (identity.tenantId != tenant) throw ManagementException("not_found", 404)
        if (identity.role !in setOf("owner", "admin")) throw ManagementException("forbidden", 403)
        return identity.memberId
    }

    private fun handle(tenant: UUID, body: JsonNode?, request: HttpServletRequest): ResponseEntity<*> {
        val actor = authorize(tenant, request)
        val path = request.requestURI.substringAfter("/organizations/$tenant")
        val etag = request.getHeader("If-Match")?.let {
            Regex("\"(?:team|vendor)-([0-9]+)\"").matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() ?: throw ManagementException("invalid_request", 400, "If-Match")
        }
        val result = store.command(tenant, actor, "${request.method} $path", body ?: mapper.createObjectNode(), request.getHeader("Idempotency-Key"), etag)
        if (request.method == "DELETE" || path.endsWith("/revoke")) return ResponseEntity.noContent().build<Void>()
        val created = request.method == "POST" && path in listOf("/teams", "/vendors")
        val response = ResponseEntity.status(if (created) 201 else 200).header("Cache-Control", "no-store")
        if (created) response.location(URI(request.requestURI + "/" + if (path == "/teams") result.path("teamId").asString() else result.path("vendor").path("vendorId").asString()))
        if (path.startsWith("/vendors")) response.eTag("vendor-${result.path("vendor").path("version").asLong()}")
        return response.body(result)
    }
}

@RestControllerAdvice(assignableTypes = [ManagementController::class])
@org.springframework.core.annotation.Order(-20)
class ManagementErrors {
    @ExceptionHandler(ManagementException::class)
    fun domain(error: ManagementException): ResponseEntity<*> = response(error.status, error.code, error.field)
    @ExceptionHandler(org.springframework.dao.DataAccessException::class)
    fun database(): ResponseEntity<*> = response(503, "unavailable", null)
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException::class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException::class)
    fun malformed(): ResponseEntity<*> = response(400, "invalid_request", null)
    private fun response(status: Int, code: String, field: String?): ResponseEntity<*> {
        val requestId = UUID.randomUUID().toString()
        val builder = ResponseEntity.status(status).header("X-Request-Id", requestId).header("Cache-Control", "no-store")
        if (status == 503) builder.header("Retry-After", "2")
        val fields = if (field == null) emptyList() else listOf(mapOf("field" to field, "code" to code))
        return builder.body(mapOf<String, Any>("error" to mapOf<String, Any>("code" to code, "message" to "관리 요청을 처리할 수 없습니다.",
            "fieldErrors" to fields), "requestId" to requestId))
    }
}
