package com.team376.pulsemetry.telemetry.config

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import jakarta.servlet.http.HttpServletRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.mock.env.MockEnvironment
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/** ingest의 플랫폼 trace 조립(ADR 0069). Gateway와 같은 규칙이고 service.name만 다르다. */
class TracingConfigTest {

    @Test
    fun enabledWithoutEndpointRefusesToStart() {
        val enabled = MockEnvironment().withProperty("management.tracing.export.enabled", "true")
        assertThatThrownBy { TracingConfig(enabled) }.hasMessageContaining("PULSEMETRY_TRACING_OTLP_TRACES_ENDPOINT")
        TracingConfig(MockEnvironment())
    }

    @Test
    fun serviceNameIsIngest() {
        val attributes = platformResource(MockEnvironment().withProperty("spring.application.name", "pulsemetry-telemetry-ingest"), "telemetry-ingest").attributes
        assertThat(attributes.get(AttributeKey.stringKey("service.name"))).isEqualTo("telemetry-ingest")
    }

    @Test
    fun inboundTraceparentIsHidden() {
        val request = MockHttpServletRequest("POST", "/v1/traces").apply {
            addHeader("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")
            addHeader("Authorization", "Bearer token")
        }
        val chain = MockFilterChain()
        InboundTraceHeaderFilter().doFilter(request, MockHttpServletResponse(), chain)
        assertThat((chain.request as HttpServletRequest).getHeader("traceparent")).isNull()
        assertThat((chain.request as HttpServletRequest).getHeader("Authorization")).isEqualTo("Bearer token")
    }

    @Test
    fun exportedSpansLoseExceptionText() {
        val sink = InMemorySpanExporter.create()
        val beans = StaticListableBeanFactory().apply { addBean("sink", sink) }
        val exporter = TracingConfig(MockEnvironment()).platformSpanExporters(beans.getBeanProvider(SpanExporter::class.java)).list().single()
        // provider 를 닫으면 exporter 도 닫혀 모은 span 이 비므로, 단언을 마친 뒤에 닫는다.
        val provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()
        provider.get("test").spanBuilder("probe").startSpan().apply {
            recordException(IllegalStateException("CANARY in message"))
            setStatus(StatusCode.ERROR, "CANARY in status")
            addEvent("kept", Attributes.of(AttributeKey.stringKey("k"), "v"))
        }.end()
        provider.get("test").spanBuilder("clean").startSpan().end()
        val probe = sink.finishedSpanItems.single { it.name == "probe" }
        // status code·이벤트 이름·exception.type·다른 이벤트는 그대로이고 원문만 빠진다.
        assertThat(probe.status.statusCode).isEqualTo(StatusCode.ERROR)
        assertThat(probe.status.description).isEmpty()
        val exception = probe.events.single { it.name == "exception" }
        assertThat(exception.attributes.get(AttributeKey.stringKey("exception.type"))).isEqualTo("java.lang.IllegalStateException")
        assertThat(exception.attributes.asMap().keys.map { it.key }).doesNotContain("exception.message", "exception.stacktrace")
        assertThat(probe.events.single { it.name == "kept" }.attributes.get(AttributeKey.stringKey("k"))).isEqualTo("v")
        assertThat(probe.toString()).doesNotContain("CANARY")
        assertThat(sink.finishedSpanItems.single { it.name == "clean" }.status.statusCode).isEqualTo(StatusCode.UNSET)
        provider.close()
    }
}
