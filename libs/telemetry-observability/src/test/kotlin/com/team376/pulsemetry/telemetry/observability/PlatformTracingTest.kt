package com.team376.pulsemetry.telemetry.observability

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.Test

/** 플랫폼 trace 공통 규칙(ADR 0069). */
class PlatformTracingTest {

    @Test
    fun enabledTracingNeedsEndpoint() {
        assertThatThrownBy { PlatformTracing.requireEndpoint(true, " ") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("PULSEMETRY_TRACING_OTLP_TRACES_ENDPOINT")
        assertThatCode { PlatformTracing.requireEndpoint(true, "http://collector:4318/v1/traces") }.doesNotThrowAnyException()
        assertThatCode { PlatformTracing.requireEndpoint(false, null) }.doesNotThrowAnyException()
    }

    @Test
    fun resourceSkipsBlankValuesAndFixesServiceName() {
        assertThat(PlatformTracing.resourceAttributes(PlatformTracing.GATEWAY_SERVICE, "dev", "", null, "gateway-0")).containsExactly(
            entry("service.name", "telemetry-gateway"),
            entry("deployment.environment.name", "dev"),
            entry("k8s.pod.name", "gateway-0"),
        )
    }

    @Test
    fun probesAndManagementAreNotTraced() {
        assertThat(PlatformTracing.tracesServerPath("/v1/logs")).isTrue()
        assertThat(PlatformTracing.tracesServerPath("/api/v1/healthz")).isFalse()
        assertThat(PlatformTracing.tracesServerPath("/actuator")).isFalse()
        assertThat(PlatformTracing.tracesServerPath("/actuator/prometheus")).isFalse()
        assertThat(PlatformTracing.tracesServerPath("/actuatorx")).isTrue()
    }

    @Test
    fun inboundTraceHeadersAreCaseInsensitive() {
        listOf("traceparent", "TraceParent", "tracestate", "Baggage", "b3", "X-B3-TraceId").forEach {
            assertThat(PlatformTracing.isInboundTraceHeader(it)).describedAs(it).isTrue()
        }
        assertThat(PlatformTracing.isInboundTraceHeader("authorization")).isFalse()
    }

    @Test
    fun onlyOneWellSizedTraceparentIsUsed() {
        val valid = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
        assertThat(PlatformTracing.singleTraceparent(listOf(valid.toByteArray()))).isEqualTo(valid)
        assertThat(PlatformTracing.singleTraceparent(emptyList())).isNull()
        assertThat(PlatformTracing.singleTraceparent(listOf(valid.toByteArray(), valid.toByteArray()))).isNull()
        assertThat(PlatformTracing.singleTraceparent(listOf((valid + "-extra").toByteArray()))).isNull()
        assertThat(PlatformTracing.singleTraceparent(listOf(ByteArray(64 * 1024)))).isNull()
    }
}
