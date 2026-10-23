package com.team376.pulsemetry.telemetry.gateway.config

import io.opentelemetry.api.common.AttributeKey
import jakarta.servlet.http.HttpServletRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
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
}
