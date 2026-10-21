package com.team376.pulsemetry.telemetry.gateway.config

import com.team376.pulsemetry.telemetry.observability.MicrometerTelemetryObserver
import com.team376.pulsemetry.telemetry.observability.TelemetryMetric
import com.team376.pulsemetry.telemetry.observability.TelemetryObserver
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

/**
 * 운영 계측 조립(ADR 0068). scrape 는 관리 포트에만 있다. 관리 컨텍스트에는 actuator 만 있어 업무 경로가 없고,
 * 업무 포트의 `/actuator` 는 계약 밖 경로라 `GatewayHealthController` 가 `404 text/plain` 으로 받는다.
 * 부모 컨텍스트의 서블릿 필터는 관리 포트에 걸리지 않는다 — 관리 포트를 막는 필터를 여기에 두지 않는다.
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
            MicrometerTelemetryObserver(registry, gauges = setOf(TelemetryMetric.GATEWAY_INFLIGHT))
        } else {
            TelemetryObserver.NONE
        }

    companion object {
        const val ENABLED: String = "pulsemetry.observability.enabled"
    }
}
