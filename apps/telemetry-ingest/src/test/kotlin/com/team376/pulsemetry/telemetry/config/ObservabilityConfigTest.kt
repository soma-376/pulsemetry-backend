package com.team376.pulsemetry.telemetry.config

import com.team376.pulsemetry.telemetry.observability.MicrometerTelemetryObserver
import com.team376.pulsemetry.telemetry.observability.TelemetryObserver
import com.team376.pulsemetry.telemetry.observability.TelemetryMetric
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

/** 계측 on/off 와 관리 포트 충돌 거부(ADR 0068). */
class ObservabilityConfigTest {

    @Test
    fun observerFollowsTheSwitch() {
        val registry = SimpleMeterRegistry()
        assertThat(ObservabilityConfig(MockEnvironment()).telemetryObserver(registry)).isInstanceOf(MicrometerTelemetryObserver::class.java)
        assertThat(registry.get(TelemetryMetric.PIPELINE_USAGE_CONTRACT.meterName).counters()).hasSize(8)
            .allSatisfy { assertThat(it.count()).isZero() }
        val off = MockEnvironment().withProperty(ObservabilityConfig.ENABLED, "false")
        val disabledRegistry = SimpleMeterRegistry()
        assertThat(ObservabilityConfig(off).telemetryObserver(disabledRegistry)).isSameAs(TelemetryObserver.NONE)
        assertThat(disabledRegistry.find(TelemetryMetric.PIPELINE_USAGE_CONTRACT.meterName).counters()).isEmpty()
    }

    @Test
    fun managementPortMustDifferFromServerPort() {
        val same = MockEnvironment().withProperty("server.port", "4316").withProperty("management.server.port", "4316")
        assertThatThrownBy { ObservabilityConfig(same) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
