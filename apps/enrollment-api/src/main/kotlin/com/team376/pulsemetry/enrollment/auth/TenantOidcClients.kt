package com.team376.pulsemetry.enrollment.auth

import com.team376.pulsemetry.persistence.enrollment.repository.TenantOidcConfiguration
import com.team376.pulsemetry.persistence.enrollment.repository.UserAuthRepository
import com.team376.pulsemetry.security.user.UserAuthException
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.ClientRegistrations
import java.net.URI
import java.util.UUID

/** 회사 활성 상태는 매번 DB에서 확인한다. discovery 결과만 제한된 캐시에 보관한다. */
class TenantOidcClients(private val repository: UserAuthRepository, private val properties: OidcProperties) : ClientRegistrationRepository {
    private class Cached(val configuration: TenantOidcConfiguration, val secret: String, val registration: ClientRegistration)
    private val cache = object : LinkedHashMap<UUID, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, Cached>?) = size > 256
    }

    fun configuration(tenant: UUID): TenantOidcConfiguration = repository.tenantOidc(tenant)
        ?: throw UserAuthException("member_not_allowed", 403)

    override fun findByRegistrationId(registrationId: String): ClientRegistration? {
        val tenant = try { UUID.fromString(registrationId) } catch (_: IllegalArgumentException) { return null }
        return registration(configuration(tenant))
    }

    @Synchronized
    fun registration(configuration: TenantOidcConfiguration): ClientRegistration {
        val key = configuration.secretRef.removePrefix("config:")
        val secret = properties.clientSecrets[key]?.takeIf { it.isNotBlank() }
            ?: throw UserAuthException("auth_unavailable", 503)
        if (!configuration.secretRef.matches(Regex("config:[A-Za-z0-9_-]+")))
            throw UserAuthException("auth_unavailable", 503)
        cache[configuration.tenantId]?.let {
            if (it.configuration == configuration && it.secret == secret) return it.registration
        }
        requireOidcUrl(configuration.issuer, properties.allowInsecureLocalhost)
        try {
            val registration = ClientRegistrations.fromIssuerLocation(configuration.issuer)
                .registrationId(configuration.tenantId.toString())
                .clientId(configuration.clientId).clientSecret(secret).scope("openid", "profile", "email")
                .redirectUri("${properties.callbackBaseUrl.trimEnd('/')}/api/v1/auth/oidc/callback/${properties.callbackRegistrationId}").build()
            cache[configuration.tenantId] = Cached(configuration, secret, registration)
            return registration
        } catch (_: Exception) {
            // discovery 응답이나 비밀을 예외 응답에 포함하지 않는다.
            throw UserAuthException("auth_unavailable", 503)
        }
    }
}

internal fun requireOidcUrl(value: String, localAllowed: Boolean) {
    val uri = URI(value)
    require(uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
        (uri.scheme == "https" || (localAllowed && uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "[::1]")))) {
        "OIDC 주소는 HTTPS여야 한다. 로컬 HTTP 예외는 loopback에만 허용한다"
    }
}
