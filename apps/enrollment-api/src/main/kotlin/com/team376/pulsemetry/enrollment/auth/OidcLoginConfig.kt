package com.team376.pulsemetry.enrollment.auth

import com.team376.pulsemetry.security.user.UserAuthException
import com.team376.pulsemetry.security.user.AuthRateLimiter
import com.team376.pulsemetry.security.user.UserAuthService
import com.team376.pulsemetry.persistence.enrollment.repository.UserAuthRepository
import com.team376.pulsemetry.persistence.enrollment.repository.TenantOidcConfiguration
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.ObjectPostProcessor
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.context.NullSecurityContextRepository
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy
import org.springframework.security.oauth2.client.oidc.session.InMemoryOidcSessionRegistry
import org.springframework.security.oauth2.client.oidc.session.OidcSessionRegistry
import org.springframework.security.oauth2.client.oidc.session.OidcSessionInformation
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession
import org.springframework.session.web.http.DefaultCookieSerializer
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.UriUtils
import java.io.Serializable
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import java.util.Locale

@ConfigurationProperties("pulsemetry.oidc")
class OidcProperties {
    var enabled = false
    var callbackBaseUrl = ""
    var failureRedirectUri = ""
    var allowInsecureLocalhost = false
    var callbackRegistrationId = "oidc"
    var clientSecrets: Map<String, String> = emptyMap()

}

/** OIDC 쿠키는 로그인 왕복에만 사용한다. 업무 API의 SecurityContext에는 저장하지 않는다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pulsemetry.oidc", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(OidcProperties::class)
@EnableJdbcHttpSession(maxInactiveIntervalInSeconds = 600, tableName = "enrollment.oidc_login_sessions")
class OidcLoginConfig {
    @Bean
    fun oidcClients(p: OidcProperties, user: UserAuthProperties, repository: UserAuthRepository): TenantOidcClients {
        requireOidcUrl(p.callbackBaseUrl, p.allowInsecureLocalhost)
        require(p.callbackRegistrationId.matches(Regex("[A-Za-z0-9_-]+")))
        if (p.failureRedirectUri.isNotBlank()) {
            requireOidcUrl(p.failureRedirectUri, p.allowInsecureLocalhost)
            require(p.failureRedirectUri in user.allowedRedirectUris) { "오류 복귀 주소도 정확한 허용 주소여야 한다" }
        }
        return TenantOidcClients(repository, p)
    }

    @Bean
    fun cookieSerializer(p: OidcProperties) = DefaultCookieSerializer().apply {
        setCookieName("PULSEMETRY_OIDC")
        setCookiePath("/api/v1/auth/oidc")
        setUseHttpOnlyCookie(true)
        setUseSecureCookie(URI(p.callbackBaseUrl).scheme == "https")
        setSameSite("Lax")
    }

    @Bean
    @Order(1)
    fun oidcSecurity(http: HttpSecurity, p: OidcProperties, clients: TenantOidcClients,
        auth: UserAuthService, limiter: AuthRateLimiter): SecurityFilterChain {
        val resolver = LoginRequestResolver(clients, auth)
        http.securityMatcher("/api/v1/auth/oidc/**")
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .csrf { it.disable() }.formLogin { it.disable() }.httpBasic { it.disable() }.logout { it.disable() }
            .requestCache { it.disable() }
            .sessionManagement {
                it.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                    .sessionAuthenticationStrategy(NullAuthenticatedSessionStrategy())
            }
            .securityContext { it.securityContextRepository(NullSecurityContextRepository()) }
            .oauth2Login { login ->
                login.clientRegistrationRepository(clients)
                    .securityContextRepository(NullSecurityContextRepository())
                    .oidcSessionRegistry(DiscardOidcSessions())
                    .authorizedClientRepository(DiscardAuthorizedClients())
                    .withObjectPostProcessor(object : ObjectPostProcessor<OAuth2AuthorizationRequestRedirectFilter> {
                        override fun <O : OAuth2AuthorizationRequestRedirectFilter> postProcess(filter: O): O {
                            filter.setAuthenticationFailureHandler { _, response, exception ->
                                val cause = exception.cause as? UserAuthException
                                oidcError(response, cause?.status ?: 503, cause?.code ?: "auth_unavailable")
                            }
                            return filter
                        }
                    })
                    .authorizationEndpoint { it.authorizationRequestResolver(resolver) }
                    .redirectionEndpoint { it.baseUri("/api/v1/auth/oidc/callback/*") }
                    .successHandler { request, response, authentication ->
                        val session = request.getSession(false)
                        val flow = session?.getAttribute(FLOW_KEY) as? LoginFlow
                        session?.invalidate()
                        try {
                            if (flow == null || System.currentTimeMillis() - flow.createdAt > 600_000) throw UserAuthException("invalid_credentials")
                            val token = authentication as OAuth2AuthenticationToken
                            val user = token.principal as OidcUser
                            val registration = clients.configuration(flow.tenantId)
                            if (registration != flow.configuration) throw UserAuthException("invalid_credentials")
                            if (token.authorizedClientRegistrationId != flow.registrationId ||
                                user.idToken.issuer.toString() != registration.issuer)
                                throw UserAuthException("invalid_credentials")
                            // 최초 연결의 검증 이메일은 ID Token에서만 받는다. 입력 힌트로 신원을 증명하지 않는다.
                            if (registration.requireVerifiedEmail &&
                                ((user.idToken.claims["email"] as? String).isNullOrBlank() ||
                                    user.idToken.claims["email_verified"] != true))
                                throw UserAuthException("invalid_credentials")
                            val callback = auth.authorizeOidc(flow.tenantId, user.idToken.issuer.toString(), user.subject ?: throw UserAuthException("invalid_credentials"),
                                flow.redirectUri, flow.clientState, flow.challenge, "S256", flow.memberId, flow.loginEmail,
                                (user.idToken.claims["email"] as? String).takeIf { user.idToken.claims["email_verified"] == true })
                            response.sendRedirect(callback)
                        } catch (e: UserAuthException) {
                            oidcFailure(response, flow, p, e.status, e.code)
                        } catch (_: Exception) {
                            oidcFailure(response, flow, p, 503, "auth_unavailable")
                        }
                    }
                    .failureHandler { request, response, exception ->
                        val session = request.getSession(false)
                        val flow = session?.getAttribute(FLOW_KEY) as? LoginFlow
                        session?.invalidate()
                        val code = (exception as? OAuth2AuthenticationException)?.error?.errorCode
                        val unavailable = code in setOf("invalid_token_response", "server_error", "temporarily_unavailable")
                        oidcFailure(response, flow, p, if (unavailable) 503 else 401,
                            if (unavailable) "auth_unavailable" else if (code == "access_denied") "login_cancelled" else "invalid_credentials")
                    }
            }
        http.addFilterBefore(OidcRequestGuard(limiter, clients), OAuth2AuthorizationRequestRedirectFilter::class.java)
        return http.build()
    }


}

private const val FLOW_KEY = "pulsemetry.oidc.flow"

/** 비밀은 없지만 state/challenge도 로그에 자동 출력하지 않는다. */
private class LoginFlow(val tenantId: UUID, val registrationId: String, val redirectUri: String,
    val clientState: String, val challenge: String, val configuration: TenantOidcConfiguration,
    val memberId: UUID?, val loginEmail: String?, val createdAt: Long = System.currentTimeMillis()) : Serializable

private class LoginRequestResolver(private val clients: TenantOidcClients,
    private val auth: UserAuthService) : OAuth2AuthorizationRequestResolver {
    private val delegate = DefaultOAuth2AuthorizationRequestResolver(clients, "/api/v1/auth/oidc/authorize").apply {
        setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce())
    }

    override fun resolve(request: HttpServletRequest): OAuth2AuthorizationRequest? {
        if (request.requestURI.removePrefix(request.contextPath) != "/api/v1/auth/oidc/authorize") return null
        if (request.method != "GET") throw UserAuthException("invalid_request", 400)
        fun parameter(name: String): String {
            val values = request.getParameterValues(name)
            if (values == null || values.size != 1 || values[0].isBlank()) throw UserAuthException("invalid_request", 400)
            return values[0]
        }
        val tenant = try { UUID.fromString(parameter("tenant_id")) } catch (_: IllegalArgumentException) {
            throw UserAuthException("invalid_request", 400)
        }
        val configuration = clients.configuration(tenant)
        val registrationId = tenant.toString()
        val redirect = parameter("redirect_uri")
        val state = parameter("state")
        val challenge = parameter("code_challenge")
        auth.validateAuthorizationRequest(redirect, state, challenge, parameter("code_challenge_method"))
        val hints = request.getParameterValues("login_hint")
        val loginHint = hints?.let {
            if (it.size != 1) throw UserAuthException("invalid_request", 400)
            val email = it.single().trim().lowercase(Locale.ROOT)
            if (email.length > 254 || email.any { character -> character.isISOControl() } ||
                !email.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")))
                throw UserAuthException("invalid_request", 400)
            email
        }
        val memberId = loginHint?.let { auth.oidcLoginMember(tenant, it) }
        val resolved = delegate.resolve(request, registrationId) ?: throw UserAuthException("invalid_request", 400)
        // 미등록 이메일은 이동 전에 거부한다. 첫 연결 대상은 서버 로그인 세션에 고정한다.
        val authorization = if (loginHint == null) resolved else OAuth2AuthorizationRequest.from(resolved)
            .additionalParameters { it["login_hint"] = loginHint }
            // 이메일의 '+'가 form query 디코딩에서 공백으로 바뀌지 않도록 엄격히 인코딩한다.
            .authorizationRequestUri { uri ->
                uri.replaceQueryParam("login_hint", UriUtils.encode(loginHint, UTF_8)).build()
            }.build()
        // 브라우저마다 한 번의 로그인만 진행한다. 이전 왕복 쿠키/요청을 재사용하지 않는다.
        request.getSession(false)?.invalidate()
        request.getSession(true).setAttribute(FLOW_KEY, LoginFlow(tenant, registrationId, redirect, state, challenge, configuration, memberId, loginHint))
        return authorization
    }

    override fun resolve(request: HttpServletRequest, clientRegistrationId: String): OAuth2AuthorizationRequest? = resolve(request)
}

private class OidcRequestGuard(private val limiter: AuthRateLimiter, private val clients: TenantOidcClients) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        response.setHeader("Cache-Control", "no-store")
        response.setHeader("Pragma", "no-cache")
        response.setHeader("Referrer-Policy", "no-referrer")
        try {
            limiter.entry(request.remoteAddr)
            if (request.requestURI.removePrefix(request.contextPath).startsWith("/api/v1/auth/oidc/callback/")) {
                val session = request.getSession(false)
                val flow = session?.getAttribute(FLOW_KEY) as? LoginFlow
                if (flow != null && clients.configuration(flow.tenantId) != flow.configuration) {
                    session.invalidate()
                    throw UserAuthException("invalid_credentials")
                }
            }
            chain.doFilter(request, response)
        } catch (e: UserAuthException) {
            e.retryAfter?.let { response.setHeader("Retry-After", it.toString()) }
            oidcError(response, e.status, e.code)
        } catch (_: Exception) {
            oidcError(response, 503, "auth_unavailable")
        }
    }
}

private fun oidcError(response: HttpServletResponse, status: Int, code: String) {
    if (response.isCommitted) return
    response.status = status
    response.contentType = "application/json"
    response.characterEncoding = "UTF-8"
    if (status == 503) response.setHeader("Retry-After", "1")
    response.writer.write("""{"error":"$code","message":"SSO 인증 요청을 처리할 수 없습니다."}""")
}

/** 복귀 주소는 시작 시 검증한 서버 상태 또는 고정 설정만 사용한다. 요청에서 가져오지 않는다. */
private fun oidcFailure(response: HttpServletResponse, flow: LoginFlow?, p: OidcProperties, status: Int, code: String) {
    if (response.isCommitted) return
    if (flow != null) {
        val safeCode = when (code) {
            "member_not_allowed", "auth_unavailable", "login_cancelled" -> code
            else -> "invalid_credentials"
        }
        response.sendRedirect("${flow.redirectUri}?error=$safeCode&state=${URLEncoder.encode(flow.clientState, UTF_8)}")
    } else if (p.failureRedirectUri.isNotBlank()) {
        response.sendRedirect("${p.failureRedirectUri}?error=login_expired")
    } else oidcError(response, status, code)
}

/** 제공자 토큰은 서비스 토큰이 아니다. HttpSession/DB에 장기 보관하지 않는다. */
private class DiscardAuthorizedClients : OAuth2AuthorizedClientRepository {
    override fun <T : OAuth2AuthorizedClient> loadAuthorizedClient(id: String, principal: Authentication, request: HttpServletRequest): T? = null
    override fun saveAuthorizedClient(client: OAuth2AuthorizedClient, principal: Authentication, request: HttpServletRequest, response: HttpServletResponse) = Unit
    override fun removeAuthorizedClient(id: String, principal: Authentication, request: HttpServletRequest, response: HttpServletResponse) = Unit
}

/** IdP 로그아웃 연동은 별도 범위다. 장기 OIDC 세션/ID token 레지스트리를 생성하지 않는다. */
private class DiscardOidcSessions : OidcSessionRegistry by InMemoryOidcSessionRegistry() {
    override fun saveSessionInformation(info: OidcSessionInformation) = Unit
}
