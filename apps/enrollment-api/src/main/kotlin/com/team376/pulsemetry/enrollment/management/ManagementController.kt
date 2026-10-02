package com.team376.pulsemetry.enrollment.management

import com.team376.pulsemetry.connector.vendor.SeatConnectors
import com.team376.pulsemetry.persistence.enrollment.installation.InstallationNotifier
import com.team376.pulsemetry.persistence.enrollment.mail.InvitationMailer
import com.team376.pulsemetry.persistence.enrollment.alert.AlertRuleStore
import com.team376.pulsemetry.persistence.enrollment.management.ManagementException
import org.springframework.beans.factory.ObjectProvider
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
    /** 초대 메일의 수락 링크가 가리키는 프론트 주소. 메일 기능을 같이 켰을 때 필요하다 */
    var invitationAcceptUrl = ""
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pulsemetry.management", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(ManagementProperties::class)
class ManagementConfig {
    /** 알림 규칙·목록 (ADR 0051). */
    @Bean
    fun alertRuleStore(jdbc: JdbcClient, manager: PlatformTransactionManager, clock: Clock) = AlertRuleStore(jdbc, manager, clock)

    @Bean
    fun managementStore(jdbc: JdbcClient, manager: PlatformTransactionManager, mapper: ObjectMapper, clock: Clock,
        properties: ManagementProperties, invitationMail: ObjectProvider<InvitationMailer>,
        installationNotifier: ObjectProvider<InstallationNotifier>, seatConnectors: ObjectProvider<SeatConnectors>): ManagementStore = ManagementStore(jdbc, manager, mapper, clock,
            properties.responseEncryptionKey, { InitialOnboardingManifest.create(properties.onboardingOtlpEndpoint, mapper) }, invitationMail.ifAvailable,
            installationNotifier.ifAvailable,
            // 커넥터가 조립된 배포(벤더 연결 기능)에서만 주기 실행이 벤더 제어 대상을 부른다(ADR 0049).
            vendorControl = seatConnectors.ifAvailable != null)
}

@RestController
@ConditionalOnProperty(prefix = "pulsemetry.management", name = ["enabled"], havingValue = "true")
@RequestMapping("/api/v1/organizations/{organizationId}")
class ManagementController(private val auth: UserAuthService, private val store: ManagementStore, private val mapper: ObjectMapper) {
    @RequestMapping(path = ["/teams", "/member-team-assignments", "/invitations/batch", "/invitations/{invitationId}/revoke", "/invitations/{invitationId}/reissue", "/members/{memberId}/installation-invitations", "/vendors", "/onboarding/complete",
        "/installation-update-notifications", "/vendors/{vendorId}/connection/sync", "/vendors/{vendorId}/seats", "/vendors/{vendorId}/seats/import",
        "/vendors/{vendorId}/seats/{seatId}/release", "/seat-reclaims/preview", "/seat-reclaims", "/seat-reclaims/{operationId}/restore",
        "/operations/{operationId}/targets/{targetId}/confirm", "/operations/{operationId}/targets/{targetId}/cancel"], method = [RequestMethod.POST])
    fun post(@PathVariable organizationId: UUID, @RequestBody(required = false) body: JsonNode?, request: HttpServletRequest) = handle(organizationId, body, request)

    @RequestMapping(path = ["/teams/{teamId}"], method = [RequestMethod.PATCH, RequestMethod.DELETE])
    fun team(@PathVariable organizationId: UUID, @RequestBody(required = false) body: JsonNode?, request: HttpServletRequest) = handle(organizationId, body, request)

    @RequestMapping(path = ["/members/{memberId}", "/vendors/{vendorId}/seats/{seatId}"], method = [RequestMethod.PATCH])
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
        @RequestParam(required = false) memberStatus: String?, request: HttpServletRequest): ResponseEntity<JsonNode> {
        authorize(organizationId, request)
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(store.invitations(organizationId, limit, cursor, status, memberStatus))
    }

    private fun authorize(tenant: UUID, request: HttpServletRequest): UUID = managementActor(auth, tenant, request)

    private fun handle(tenant: UUID, body: JsonNode?, request: HttpServletRequest): ResponseEntity<*> {
        val actor = authorize(tenant, request)
        val path = request.requestURI.substringAfter("/organizations/$tenant")
        val etag = request.getHeader("If-Match")?.let {
            Regex("\"(?:team|vendor)-([0-9]+)\"").matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() ?: throw ManagementException("invalid_request", 400, "If-Match")
        }
        val result = store.command(tenant, actor, "${request.method} $path", body ?: mapper.createObjectNode(), request.getHeader("Idempotency-Key"), etag)
        if (request.method == "DELETE" || path.endsWith("/revoke")) return ResponseEntity.noContent().build<Void>()
        // 안내·동기화·회수·복원 요청은 접수만 했다 — 결과는 작업 상태 조회(dashboard-api)로 본다(ADR 0039·0043·0048·0049). 멱등 재시도도 같은 작업을 가리킨다.
        if (path == "/installation-update-notifications" || (path.startsWith("/vendors/") && path.endsWith("/connection/sync")) ||
            path == "/seat-reclaims" || (path.startsWith("/seat-reclaims/") && path.endsWith("/restore"))) {
            return ResponseEntity.accepted().header("Cache-Control", "no-store")
                .location(URI("/api/v1/organizations/$tenant/operations/${result.path("operationId").asString()}")).body(result)
        }
        // 좌석 명령(ADR 0048): 새 배정은 201 + Location, 좌석 응답은 ETag "seat-{판}". 가져오기는 200 이다.
        if (Regex("/vendors/[^/]+/seats(/.*)?").matches(path)) {
            val seat = result.path("seat")
            val status = if (request.method == "POST" && path.endsWith("/seats") && seat.path("version").asLong() == 1L) 201 else 200
            val response = ResponseEntity.status(status).header("Cache-Control", "no-store")
            if (!seat.isMissingNode) response.eTag("seat-${seat.path("version").asLong()}")
            if (status == 201) response.location(URI(request.requestURI + "/" + seat.path("seatAssignmentId").asString()))
            return response.body(result)
        }
        val created = request.method == "POST" && path in listOf("/teams", "/vendors")
        val response = ResponseEntity.status(if (created) 201 else 200).header("Cache-Control", "no-store")
        if (created) response.location(URI(request.requestURI + "/" + if (path == "/teams") result.path("teamId").asString() else result.path("vendor").path("vendorId").asString()))
        if (path.startsWith("/vendors")) response.eTag("vendor-${result.path("vendor").path("version").asLong()}")
        return response.body(result)
    }
}

/** 관리 요청의 행위자. 토큰의 조직이 경로와 다르면 404, owner/admin 이 아니면 403 이다 (ADR 0026). 저장 연산이 DB 에서 한 번 더 확인한다. */
internal fun managementActor(auth: UserAuthService, tenant: UUID, request: HttpServletRequest): UUID {
    val header = request.getHeader("Authorization")
    if (header == null || !header.startsWith("Bearer ")) throw ManagementException("unauthenticated", 401)
    val identity = try { auth.verify(header.removePrefix("Bearer ")) } catch (_: UserAuthException) { throw ManagementException("unauthenticated", 401) }
    if (identity.tenantId != tenant) throw ManagementException("not_found", 404)
    if (identity.role !in setOf("owner", "admin")) throw ManagementException("forbidden", 403)
    return identity.memberId
}

@RestControllerAdvice(assignableTypes = [ManagementController::class, VendorConnectionController::class, AlertRuleController::class])
@org.springframework.core.annotation.Order(-20)
class ManagementErrors {
    @ExceptionHandler(ManagementException::class)
    fun domain(error: ManagementException): ResponseEntity<*> = response(error.status, error.code, error.field, error.detail)
    @ExceptionHandler(org.springframework.dao.DataAccessException::class)
    fun database(): ResponseEntity<*> = response(503, "unavailable", null)
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException::class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException::class)
    fun malformed(): ResponseEntity<*> = response(400, "invalid_request", null)
    private fun response(status: Int, code: String, field: String?, detail: Any? = null): ResponseEntity<*> {
        val requestId = UUID.randomUUID().toString()
        val builder = ResponseEntity.status(status).header("X-Request-Id", requestId).header("Cache-Control", "no-store")
        if (status == 503) builder.header("Retry-After", "2")
        val fields = if (field == null) emptyList() else listOf(mapOf("field" to field, "code" to code))
        val body = mutableMapOf<String, Any>("error" to mapOf<String, Any>("code" to code, "message" to "관리 요청을 처리할 수 없습니다.",
            "fieldErrors" to fields), "requestId" to requestId)
        // 구조화된 사유(예: CSV 가져오기의 행별 오류)가 있을 때만 가산한다.
        detail?.let { body["details"] = it }
        return builder.body(body)
    }
}
