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
    fun onlyOneWellFormedTraceparentIsUsed() {
        val valid = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
        assertThat(PlatformTracing.traceparentHeader(listOf(valid.toByteArray())))
            .isEqualTo(PlatformTracing.TraceparentHeader(valid, null, 1, 55))
        // header 없음은 사유가 없다 — trace 를 끈 Gateway·이전 record 의 정상 상태다.
        assertThat(PlatformTracing.traceparentHeader(emptyList())).isEqualTo(PlatformTracing.TraceparentHeader(null, null, 0, 0))
        assertThat(PlatformTracing.traceparentHeader(listOf(valid.toByteArray(), valid.toByteArray())))
            .isEqualTo(PlatformTracing.TraceparentHeader(null, "duplicate", 2, 110))
        assertThat(PlatformTracing.traceparentHeader(listOf((valid + "-extra").toByteArray())).defect).isEqualTo("length")
        assertThat(PlatformTracing.traceparentHeader(listOf(ByteArray(0))).defect).isEqualTo("length")
        assertThat(PlatformTracing.traceparentHeader(listOf(ByteArray(64 * 1024))))
            .isEqualTo(PlatformTracing.TraceparentHeader(null, "length", 1, 64 * 1024))
    }

    @Test
    fun malformedTraceparentOfTheRightLengthIsAFormatDefect() {
        listOf(
            "00-zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz-b7ad6b7169203331-01",
            "00-00000000000000000000000000000000-b7ad6b7169203331-01",
            "00-0af7651916cd43dd8448eb211c80319c-0000000000000000-01",
            "00-0AF7651916CD43DD8448EB211C80319C-b7ad6b7169203331-01",
            "ff-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
            "00_0af7651916cd43dd8448eb211c80319c_b7ad6b7169203331_01",
        ).forEach {
            assertThat(it).hasSize(PlatformTracing.TRACEPARENT_LENGTH)
            assertThat(PlatformTracing.traceparentHeader(listOf(it.toByteArray()))).describedAs(it)
                .isEqualTo(PlatformTracing.TraceparentHeader(null, "format", 1, 55))
        }
    }
}
