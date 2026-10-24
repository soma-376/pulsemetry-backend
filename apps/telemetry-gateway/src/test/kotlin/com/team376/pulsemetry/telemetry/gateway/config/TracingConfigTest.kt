package com.team376.pulsemetry.telemetry.gateway.config

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
import java.util.Collections

/** 플랫폼 trace 조립(ADR 0069): 켜면 endpoint 필수, 공개 요청의 trace header 제거, resource 값. */
class TracingConfigTest {

    @Test
    fun enabledWithoutEndpointRefusesToStart() {
        val enabled = MockEnvironment().withProperty("management.tracing.export.enabled", "true")
        assertThatThrownBy { TracingConfig(enabled) }.hasMessageContaining("PULSEMETRY_TRACING_OTLP_TRACES_ENDPOINT")
        TracingConfig(MockEnvironment())
        TracingConfig(enabled.withProperty("management.opentelemetry.tracing.export.otlp.endpoint", "http://collector:4318/v1/traces"))
    }

    @Test
    fun inboundTraceHeadersAreHiddenFromLaterFilters() {
        val request = MockHttpServletRequest("POST", "/v1/logs").apply {
            addHeader("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")
            addHeader("TraceState", "vendor=value")
            addHeader("baggage", "k=v")
            addHeader("X-B3-TraceId", "463ac35c9f6413ad")
            addHeader("Authorization", "Bearer token")
            addHeader("Content-Type", "application/json")
        }
        val chain = MockFilterChain()

        InboundTraceHeaderFilter().doFilter(request, MockHttpServletResponse(), chain)

        val seen = chain.request as HttpServletRequest
        assertThat(seen.getHeader("traceparent")).isNull()
        assertThat(seen.getHeader("TRACESTATE")).isNull()
        assertThat(Collections.list(seen.getHeaders("baggage"))).isEmpty()
        assertThat(Collections.list(seen.headerNames).map { it.lowercase() })
            .containsExactlyInAnyOrder("authorization", "content-type")
        assertThat(seen.getHeader("Authorization")).isEqualTo("Bearer token")
    }

    @Test
    fun requestsWithoutTraceHeadersPassUnchanged() {
        val request = MockHttpServletRequest("POST", "/v1/logs").apply { addHeader("Content-Type", "application/json") }
        val chain = MockFilterChain()
        InboundTraceHeaderFilter().doFilter(request, MockHttpServletResponse(), chain)
        assertThat(chain.request).isSameAs(request)
    }

    @Test
    fun resourceUsesFixedServiceNameAndPresentKubernetesValues() {
        val environment = MockEnvironment()
            .withProperty("pulsemetry.tracing.environment", "dev")
            .withProperty("pulsemetry.tracing.cluster", "soma-376-dev-eks")
            .withProperty("pulsemetry.tracing.namespace", "")
            .withProperty("pulsemetry.tracing.pod", "telemetry-gateway-0")
            .withProperty("spring.application.name", "pulsemetry-telemetry-gateway")

        val attributes = platformResource(environment, "telemetry-gateway").attributes

        assertThat(attributes.get(AttributeKey.stringKey("service.name"))).isEqualTo("telemetry-gateway")
        assertThat(attributes.get(AttributeKey.stringKey("deployment.environment.name"))).isEqualTo("dev")
        assertThat(attributes.get(AttributeKey.stringKey("k8s.cluster.name"))).isEqualTo("soma-376-dev-eks")
        assertThat(attributes.get(AttributeKey.stringKey("k8s.pod.name"))).isEqualTo("telemetry-gateway-0")
        assertThat(attributes.get(AttributeKey.stringKey("k8s.namespace.name"))).isNull()
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
