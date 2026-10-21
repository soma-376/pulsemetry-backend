package com.team376.pulsemetry.telemetry.gateway.config

import com.team376.pulsemetry.telemetry.observability.MicrometerTelemetryObserver
import com.team376.pulsemetry.telemetry.observability.TelemetryObserver
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.env.Environment
import org.springframework.web.filter.OncePerRequestFilter

/** 관리 포트만 scrape를 허용한다. API 보안 체인과 health 의미는 바꾸지 않는다(ADR 0068). */
@Configuration(proxyBeanMethods = false)
class ObservabilityConfig {
    @Bean
    fun telemetryObserver(registry: MeterRegistry): TelemetryObserver = MicrometerTelemetryObserver(registry)

    @Bean
    fun managementSurface(environment: Environment): FilterRegistrationBean<ManagementSurfaceFilter> {
        val management = environment.getProperty("management.server.port", Int::class.java, 9404)
        val application = environment.getProperty("server.port", Int::class.java, 8080)
        require(management == 0 || management != application) { "management.server.port must differ from server.port" }
        return FilterRegistrationBean(ManagementSurfaceFilter(environment)).apply { order = Ordered.HIGHEST_PRECEDENCE }
    }
}

/** 관리 컨텍스트의 실제 포트는 서버 시작 후 정해진다. 테스트의 random port도 같은 판정을 쓴다. */
internal fun managementRequest(environment: Environment, request: HttpServletRequest): Boolean =
    request.localPort == environment.getProperty("local.management.port", Int::class.java,
        environment.getProperty("management.server.port", Int::class.java, 9404))

class ManagementSurfaceFilter(private val environment: Environment) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val management = managementRequest(environment, request)
        if ((management && (request.method != "GET" || request.requestURI != "/actuator/prometheus")) ||
            (!management && (request.requestURI == "/actuator" || request.requestURI.startsWith("/actuator/")))) {
            response.status = 404
            response.contentType = "text/plain"
            response.outputStream.write("404 not found".toByteArray(Charsets.UTF_8))
            return
        }
        chain.doFilter(request, response)
    }
}
