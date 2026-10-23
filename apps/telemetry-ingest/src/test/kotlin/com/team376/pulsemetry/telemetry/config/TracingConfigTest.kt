package com.team376.pulsemetry.telemetry.config

import io.opentelemetry.api.common.AttributeKey
import jakarta.servlet.http.HttpServletRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
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
}
