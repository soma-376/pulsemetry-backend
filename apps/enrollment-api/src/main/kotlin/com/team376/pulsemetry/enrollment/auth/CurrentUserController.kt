package com.team376.pulsemetry.enrollment.auth

import com.team376.pulsemetry.security.user.AuthRateLimiter
import com.team376.pulsemetry.security.user.UserAuthException
import com.team376.pulsemetry.security.user.UserAuthService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/** 조직 식별과 화면 세션은 검증된 AT에서 얻는다. 본문이나 프론트의 역할 값을 믿지 않는다. */
@RestController
@ConditionalOnProperty(prefix = "pulsemetry.user-auth", name = ["enabled"], havingValue = "true")
class CurrentUserController(private val auth: UserAuthService, private val jdbc: JdbcClient, private val limiter: AuthRateLimiter) {
    @GetMapping("/api/v1/auth/me")
    fun me(@RequestHeader("Authorization", required = false) header: String?, request: HttpServletRequest): CurrentUser {
        val token = header?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
        // AT 의 세션 단위로 센다. 토큰이 없거나 검증에 실패하면 진입 버킷이다(ADR 0052).
        limiter.accessToken(token, request.remoteAddr)
        if (token == null) throw UserAuthException("invalid_credentials")
        val identity = auth.verify(token)
        return jdbc.sql("""SELECT m.email,m.display_name,t.name AS organization_name FROM enrollment.members m
            JOIN enrollment.tenants t ON t.id=m.tenant_id WHERE m.id=:member AND m.tenant_id=:tenant""")
            .param("member", identity.memberId).param("tenant", identity.tenantId).query { rs, _ ->
                CurrentUser(identity.memberId.toString(), identity.tenantId.toString(), rs.getString("organization_name"),
                    rs.getString("email"), rs.getString("display_name") ?: rs.getString("email"),
                    if (identity.role in setOf("owner", "admin")) "admin" else "member")
            }.single()
    }
}

data class CurrentUser(val memberId: String, val organizationId: String, val organizationName: String,
    val email: String, val displayName: String, val role: String)
