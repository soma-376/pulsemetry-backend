package com.team376.pulsemetry.telemetry.config

import com.team376.pulsemetry.telemetry.observability.MicrometerTelemetryObserver
import com.team376.pulsemetry.telemetry.observability.TelemetryObserver
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
        val off = MockEnvironment().withProperty(ObservabilityConfig.ENABLED, "false")
        assertThat(ObservabilityConfig(off).telemetryObserver(registry)).isSameAs(TelemetryObserver.NONE)
    }

    @Test
    fun managementPortMustDifferFromServerPort() {
        val same = MockEnvironment().withProperty("server.port", "4316").withProperty("management.server.port", "4316")
        assertThatThrownBy { ObservabilityConfig(same) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
