package com.team376.pulsemetry.enrollment.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.core.env.Environment
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

/**
 * 운영 계측 조립(ADR 0068·0079). 관리 포트(`MANAGEMENT_SERVER_PORT`, 기본 9404)에서 GET `/actuator/prometheus` 만 연다.
 *
 * Boot 가 관리 컨텍스트에 넘기는 것은 Security 체인뿐이라 관리 포트의 접근 제어는 [metricsSecurityFilterChain] 이 한다 —
 * 서블릿 필터로는 막지 못한다. 업무 포트에는 actuator 가 없다 — 그 경로는 기존 컨트롤러 계층의 404 다.
 */
@Configuration(proxyBeanMethods = false)
class ObservabilityConfig(private val environment: Environment) {

    init {
        // 두 포트가 같으면 actuator 가 업무 서버에 붙는다. 기동을 거부한다.
        val management = environment.getProperty("management.server.port", Int::class.java, 9404)
        val application = environment.getProperty("server.port", Int::class.java, 8080)
        require(management == 0 || management != application) { "management.server.port must differ from server.port" }
    }

    /**
     * 관리 포트 요청만 잡는다. GET scrape 밖은 `404 text/plain` 이다. 업무 포트의 요청은 이 체인에 들지 않으므로
     * OIDC 체인(`@Order(1)`)·업무 체인(`enrollmentSecurity`)의 계약은 그대로다.
     */
    @Bean
    @Order(0)
    fun metricsSecurityFilterChain(http: HttpSecurity): SecurityFilterChain = http
        .securityMatcher { request -> managementRequest(environment, request) }
        .csrf { it.disable() }
        .requestCache { it.disable() }
        .logout { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .exceptionHandling {
            it.authenticationEntryPoint { _, response, _ -> managementNotFound(response) }
            it.accessDeniedHandler { _, response, _ -> managementNotFound(response) }
        }
        .authorizeHttpRequests {
            it.requestMatchers(HttpMethod.GET, "/actuator/prometheus").permitAll()
            it.anyRequest().denyAll()
        }
        .build()
}

/** 관리 컨텍스트의 실제 포트는 서버 시작 후 정해진다. 테스트의 random port 도 같은 판정을 쓴다. */
internal fun managementRequest(environment: Environment, request: HttpServletRequest): Boolean =
    request.localPort == environment.getProperty("local.management.port", Int::class.java,
        environment.getProperty("management.server.port", Int::class.java, 9404))

/** 관리 포트의 거부 응답. 업무 포트의 JSON 오류 본문과 섞지 않는다 — 수집 앱의 관리 포트와 같은 바이트다. */
private fun managementNotFound(response: HttpServletResponse) {
    response.status = HttpServletResponse.SC_NOT_FOUND
    response.contentType = "text/plain;charset=UTF-8"
    response.writer.write("404 not found")
}
