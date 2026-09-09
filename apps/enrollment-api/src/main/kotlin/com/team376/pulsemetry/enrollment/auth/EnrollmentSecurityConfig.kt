package com.team376.pulsemetry.enrollment.auth

import com.team376.pulsemetry.security.user.UserAuthException
import com.team376.pulsemetry.security.user.UserAuthService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.AuthorizationFilter
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/** 기존 경로의 인증은 기존 컨트롤러가 담당한다. 사용자 인증 때문에 설치 API를 잠그지 않는다. */
@Configuration(proxyBeanMethods = false)
class EnrollmentSecurityConfig {
    /** StrictHttpFirewall의 차단은 유지하되 바이너리 경로의 기존 404 계약을 보존한다. */
    @Bean
    fun enrollmentFirewallErrors() = WebSecurityCustomizer { web ->
        web.requestRejectedHandler { request, response, _ ->
            response.sendError(if (request.requestURI.startsWith("/bin/")) 404 else 400)
        }
    }

    @Bean
    fun enrollmentSecurity(http: HttpSecurity, service: ObjectProvider<UserAuthService>, properties: ObjectProvider<UserAuthProperties>): SecurityFilterChain {
        http.csrf { it.disable() }.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .requestCache { it.disable() }.formLogin { it.disable() }.httpBasic { it.disable() }.logout { it.disable() }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
        val auth = service.ifAvailable
        if (auth != null) {
            val cors = CorsConfiguration().apply {
                allowedOrigins = properties.getObject().allowedOrigins
                allowedMethods = listOf("POST", "GET", "OPTIONS")
                allowedHeaders = listOf("Content-Type", "Authorization")
                allowCredentials = false
            }
            val source = UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/v1/auth/**", cors) }
            http.cors { it.configurationSource(source) }
            // Filter 빈으로 노출하지 않는다. Boot의 전역 자동 등록을 피한다.
            http.addFilterBefore(UserAuthRequestFilter(auth), AuthorizationFilter::class.java)
        }
        return http.build()
    }
}

private class UserAuthRequestFilter(private val auth: UserAuthService) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest) = !request.servletPath.startsWith("/v1/auth/")
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        response.setHeader("Cache-Control", "no-store")
        response.setHeader("Pragma", "no-cache")
        try {
            if (request.method != "OPTIONS") auth.limitIp(request.remoteAddr)
            chain.doFilter(request, response)
        } catch (e: UserAuthException) {
            writeError(response, e.status, e.code, e.retryAfter)
        } catch (_: Exception) {
            // 드라이버 예외에 바인딩된 비밀이 섞일 수 있어 예외 원문을 응답·로그에 싣지 않는다.
            writeError(response, 503, "auth_unavailable", 1)
        }
    }
    private fun writeError(response: HttpServletResponse, status: Int, code: String, retry: Long?) {
        if (response.isCommitted) return
        response.status = status
        response.contentType = "application/json"
        retry?.let { response.setHeader("Retry-After", it.toString()) }
        response.writer.write("""{"error":"$code","message":"사용자 인증 요청을 처리할 수 없습니다."}""")
    }
}
