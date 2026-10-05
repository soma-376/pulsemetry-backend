package com.team376.pulsemetry.enrollment.auth

import com.team376.pulsemetry.persistence.enrollment.repository.UserAuthRepository
import com.team376.pulsemetry.security.user.UserAuthException
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.Locale

/** 이메일은 경로 탐색일 뿐 인증이 아니다. /v1/auth 필터가 no-store와 IP 제한을 적용한다. */
@RestController
@ConditionalOnProperty(prefix = "pulsemetry.oidc", name = ["enabled"], havingValue = "true")
class LoginDiscoveryController(private val repository: UserAuthRepository) {
    @PostMapping("/v1/auth/organizations")
    fun discover(@RequestBody request: LoginEmail): Map<String, Any> {
        val email = request.email.trim().lowercase(Locale.ROOT)
        if (email.length > 254 || !email.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")))
            throw UserAuthException("invalid_request", 400)
        val organizations = repository.loginOrganizations(email)
            .map { mapOf("organizationId" to it.id.toString(), "organizationName" to it.name) }
        return mapOf("organizations" to organizations)
    }
}

// 자동 toString에 개인정보를 남기지 않는다.
class LoginEmail(val email: String)
