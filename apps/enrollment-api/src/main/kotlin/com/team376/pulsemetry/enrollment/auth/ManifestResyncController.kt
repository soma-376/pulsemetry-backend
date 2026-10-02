package com.team376.pulsemetry.enrollment.auth

import com.fasterxml.jackson.annotation.JsonProperty
import com.team376.pulsemetry.enrollment.contract.ManifestPayload
import com.team376.pulsemetry.persistence.enrollment.repository.UserAuthRepository
import com.team376.pulsemetry.security.user.AuthRateLimiter
import com.team376.pulsemetry.security.user.UserAuthException
import com.team376.pulsemetry.security.user.UserAuthService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.util.Collections

@RestController
@ConditionalOnProperty(prefix = "pulsemetry.user-auth", name = ["enabled"], havingValue = "true")
class ManifestResyncController(private val auth: UserAuthService, private val repository: UserAuthRepository,
    private val mapper: ObjectMapper, private val contract: ManifestContractValidator, private val limiter: AuthRateLimiter) {
    @GetMapping("/v1/manifest")
    fun resync(request: HttpServletRequest): ManifestResyncResponse {
        val headers = Collections.list(request.getHeaders("Authorization"))
        val token = headers.singleOrNull()?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)
        // RT 를 소비하기 전에 센다. 토큰이 없으면 진입 버킷이다(ADR 0052).
        limiter.refreshToken(token, request.remoteAddr)
        if (token == null) throw UserAuthException("invalid_credentials")
        return auth.rotate(token) { member, session ->
            val manifest = repository.lockActiveManifest(member.tenantId)
                ?: throw UserAuthException("manifest_not_configured", 409)
            if (manifest.revision < 0 || !contract.valid(manifest.json)) throw UserAuthException("manifest_not_configured", 409)
            val payload = try { mapper.readValue(manifest.json, ManifestPayload::class.java) }
                catch (_: JacksonException) { throw UserAuthException("manifest_not_configured", 409) }
            if (!payload.satisfiesContract()) throw UserAuthException("manifest_not_configured", 409)
            val applied = payload.withConfigRevision(manifest.revision)
            repository.updateRevision(session.id, manifest.revision)
            val tokens = auth.tokens(member, session.copy(revision = manifest.revision))
            ManifestResyncResponse(applied, tokens.accessToken, tokens.refreshToken, "Bearer", tokens.expiresIn)
        }
    }
}

class ManifestResyncResponse(val manifest: ManifestPayload,
    @JsonProperty("access_token") val accessToken: String,
    @JsonProperty("refresh_token") val refreshToken: String,
    @JsonProperty("token_type") val tokenType: String,
    @JsonProperty("expires_in") val expiresIn: Long)
