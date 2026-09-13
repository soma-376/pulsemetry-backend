package com.team376.pulsemetry.dashboard.api

import com.team376.pulsemetry.dashboard.config.DashboardProperties
import com.team376.pulsemetry.security.user.UserAuthService
import com.team376.pulsemetry.security.user.UserAuthException
import com.team376.pulsemetry.security.user.UserIdentity
import jakarta.servlet.http.HttpServletRequest
import org.springframework.dao.DataAccessException
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/v1")
class DashboardController(private val auth: UserAuthService, private val p: DashboardProperties, private val jdbc: JdbcClient) {
    data class Login(val email: String, val password: String)
    @PostMapping("/auth/login") fun login(@RequestBody body: Login, request: HttpServletRequest): Map<String, Any> {
        auth.limitIp(request.remoteAddr)
        val token = auth.loginWeb(p.tenantId, body.email, body.password)
        return mapOf("access_token" to token, "token_type" to "Bearer", "expires_in" to 28800)
    }
    @GetMapping("/healthz") fun health() = mapOf("status" to "ok")
    @GetMapping("/me") fun me(@AuthenticationPrincipal identity: UserIdentity): Map<String, Any?> {
        val row = jdbc.sql("""SELECT m.email,m.display_name,t.id,t.name,t.timezone FROM enrollment.members m
            JOIN enrollment.tenants t ON t.id=m.tenant_id WHERE m.id=:id AND t.id=:tenant""")
            .param("id", identity.memberId).param("tenant", identity.tenantId).query().singleRow()
        val teams = jdbc.sql("""SELECT t.id FROM enrollment.teams t WHERE t.tenant_id=:tenant AND
            (:owner OR EXISTS (SELECT 1 FROM enrollment.team_memberships tm WHERE tm.team_id=t.id
            AND tm.member_id=:member AND tm.left_at IS NULL)) ORDER BY t.id""")
            .param("tenant", identity.tenantId).param("owner", identity.role == "owner").param("member", identity.memberId)
            .query(UUID::class.java).list()
        return mapOf("member_id" to identity.memberId, "email" to row["email"], "display_name" to row["display_name"],
            "role" to identity.role, "tenant" to mapOf("id" to row["id"], "name" to row["name"], "timezone" to row["timezone"]),
            "accessible_team_ids" to teams)
    }
}

@RestControllerAdvice
class DashboardErrors {
    @ExceptionHandler(com.team376.pulsemetry.persistence.telemetry.DashboardReadException::class)
    fun telemetry(e: com.team376.pulsemetry.persistence.telemetry.DashboardReadException) = response(e.status, e.code)
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException::class)
    fun missing(e: Exception) = response(404, "not_found")
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException::class)
    fun method(e: Exception) = response(405, "method_not_allowed")
    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception) = response(500, "internal_error")
    @ExceptionHandler(UserAuthException::class) fun auth(e: UserAuthException) = response(e.status, e.code, e.retryAfter)
    @ExceptionHandler(DataAccessException::class, org.springframework.transaction.TransactionException::class) fun unavailable(e: Exception) = response(503, "service_unavailable", 2)
    @ExceptionHandler(HttpMessageNotReadableException::class, IllegalArgumentException::class, java.time.DateTimeException::class)
    fun invalid(e: Exception) = response(400, "invalid_request")
    private fun response(status: Int, code: String, retry: Long? = null): ResponseEntity<Map<String, String>> {
        val id = UUID.randomUUID().toString()
        val builder = ResponseEntity.status(status).contentType(org.springframework.http.MediaType.APPLICATION_JSON).header("X-Request-Id", id)
        if (retry != null) builder.header("Retry-After", retry.toString())
        return builder.body(mapOf("error" to code, "message" to code, "request_id" to id))
    }
}
