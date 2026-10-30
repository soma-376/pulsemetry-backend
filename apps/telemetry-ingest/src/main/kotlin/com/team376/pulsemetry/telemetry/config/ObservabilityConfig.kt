package com.team376.pulsemetry.telemetry.config

import com.team376.pulsemetry.telemetry.observability.MicrometerTelemetryObserver
import com.team376.pulsemetry.telemetry.observability.TelemetryMetric
import com.team376.pulsemetry.telemetry.observability.TelemetryObserver
import com.team376.pulsemetry.telemetry.observability.observe
import com.team376.pulsemetry.telemetry.adapter.observation.Product
import com.team376.pulsemetry.telemetry.adapter.observation.UsageContractOutcome
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

/**
 * 운영 계측 조립(ADR 0068). 관리 포트의 접근 제어는 `SecurityConfig` 의 관리 체인이다 — Boot 가 관리 컨텍스트에 넘기는 것은
 * Security 체인뿐이라 서블릿 필터로는 관리 포트를 막지 못한다. 업무 포트의 `/actuator` 는 기본 닫힘 체인이 404 로 거부한다.
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
     * `pulsemetry.observability.enabled`(`PULSEMETRY_OBSERVABILITY_ENABLED`, 기본 true)가 false 면 `pulsemetry_*` 업무 계측만 no-op 이다.
     * JVM·Tomcat·Kafka client binder 와 scrape 는 그대로다 — 계측 on/off 비교의 대상은 이 observer 뿐이다.
     */
    @Bean
    fun telemetryObserver(registry: MeterRegistry): TelemetryObserver =
        if (environment.getProperty(ENABLED, Boolean::class.java, true)) {
            MicrometerTelemetryObserver(registry, gauges = setOf(TelemetryMetric.PIPELINE_INFLIGHT)).also { observer ->
                // 첫 불일치도 scrape 사이 increase로 잡히도록 ingest의 유한한 조합만 0으로 준비한다.
                for (product in listOf(Product.CODEX, Product.CLAUDE_CODE)) {
                    for (outcome in UsageContractOutcome.entries) {
                        observer.observe(TelemetryMetric.PIPELINE_USAGE_CONTRACT, 0.0, "product" to product.wire, "outcome" to outcome.wire)
                    }
                }
            }
        } else {
            TelemetryObserver.NONE
        }

    companion object {
        const val ENABLED: String = "pulsemetry.observability.enabled"
    }
}

/** 관리 컨텍스트의 실제 포트는 서버 시작 후 정해진다. 테스트의 random port도 같은 판정을 쓴다. */
internal fun managementRequest(environment: Environment, request: HttpServletRequest): Boolean =
    request.localPort == environment.getProperty("local.management.port", Int::class.java,
        environment.getProperty("management.server.port", Int::class.java, 9404))
