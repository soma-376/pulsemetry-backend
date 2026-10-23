package com.team376.pulsemetry.telemetry.gateway.config

import com.team376.pulsemetry.telemetry.observability.PlatformTracing
import io.micrometer.observation.ObservationPredicate
import io.micrometer.tracing.Tracer
import io.micrometer.tracing.propagation.Propagator
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.resources.Resource
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.SdkTracerProviderBuilderCustomizer
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.env.Environment
import org.springframework.http.server.observation.ServerRequestObservationContext
import org.springframework.web.filter.OncePerRequestFilter
import java.util.Collections
import java.util.Enumeration

/**
 * 플랫폼 trace 조립(ADR 0069). 기본은 꺼져 있다(`PULSEMETRY_TRACING_ENABLED`). 켜면 OTLP/HTTP로 Collector에 보낸다.
 *
 * - resource의 `service.name`은 `telemetry-gateway`로 고정하고 Kubernetes 값은 있을 때만 붙인다.
 * - health·관리 경로는 span을 만들지 않는다.
 * - 공개 요청의 trace header는 서버 observation보다 먼저 지운다 — 요청은 언제나 새 플랫폼 root다.
 */
@Configuration(proxyBeanMethods = false)
class TracingConfig(private val environment: Environment) {

    init {
        PlatformTracing.requireEndpoint(
            environment.getProperty(PlatformTracing.ENABLED_PROPERTY, Boolean::class.java, false),
            environment.getProperty(PlatformTracing.ENDPOINT_PROPERTY),
        )
    }

    /**
     * 끄면(기본) Boot 의 OTel tracer 대신 NOOP 을 먼저 둔다. `management.tracing.export.enabled=false` 만으로는 내보내기·전파만 꺼지고
     * 기본 sampler 의 span 생성과 로그 MDC(trace_id)가 남는다 — 꺼진 상태를 지금과 같게 하려면 tracer 자체가 NOOP 이어야 한다.
     */
    @Bean
    @ConditionalOnProperty(name = [PlatformTracing.ENABLED_PROPERTY], havingValue = "false", matchIfMissing = true)
    fun disabledPlatformTracer(): Tracer = Tracer.NOOP

    @Bean
    @ConditionalOnProperty(name = [PlatformTracing.ENABLED_PROPERTY], havingValue = "false", matchIfMissing = true)
    fun disabledPlatformPropagator(): Propagator = Propagator.NOOP

    @Bean
    fun platformTraceResource(): SdkTracerProviderBuilderCustomizer = SdkTracerProviderBuilderCustomizer { builder ->
        builder.addResource(platformResource(environment, PlatformTracing.GATEWAY_SERVICE))
    }

    @Bean
    fun platformTracePaths(): ObservationPredicate = ObservationPredicate { _, context ->
        context !is ServerRequestObservationContext || context.carrier?.requestURI?.let(PlatformTracing::tracesServerPath) != false
    }

    /** 서버 observation filter(`HIGHEST_PRECEDENCE + 1`)보다 앞에 둔다. */
    @Bean
    fun inboundTraceHeaderFilter(): FilterRegistrationBean<InboundTraceHeaderFilter> =
        FilterRegistrationBean(InboundTraceHeaderFilter()).apply { order = Ordered.HIGHEST_PRECEDENCE }
}

/** `pulsemetry.tracing.*`(배포가 환경변수로 준다)를 resource로 옮긴다. 빈 값은 넣지 않는다. */
internal fun platformResource(environment: Environment, serviceName: String): Resource {
    val attributes = PlatformTracing.resourceAttributes(
        serviceName = serviceName,
        environment = environment.getProperty("pulsemetry.tracing.environment"),
        cluster = environment.getProperty("pulsemetry.tracing.cluster"),
        namespace = environment.getProperty("pulsemetry.tracing.namespace"),
        pod = environment.getProperty("pulsemetry.tracing.pod"),
    )
    return Resource.create(Attributes.builder().apply { attributes.forEach { (key, value) -> put(key, value) } }.build())
}

/** 공개 요청의 trace header를 숨긴다. 다른 header와 본문은 그대로다. */
class InboundTraceHeaderFilter : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val traced = request.headerNames?.toList().orEmpty().any(PlatformTracing::isInboundTraceHeader)
        chain.doFilter(if (traced) Stripped(request) else request, response)
    }

    private class Stripped(request: HttpServletRequest) : HttpServletRequestWrapper(request) {
        override fun getHeader(name: String): String? = if (PlatformTracing.isInboundTraceHeader(name)) null else super.getHeader(name)

        override fun getHeaders(name: String): Enumeration<String> =
            if (PlatformTracing.isInboundTraceHeader(name)) Collections.emptyEnumeration() else super.getHeaders(name)

        override fun getHeaderNames(): Enumeration<String> =
            Collections.enumeration(super.getHeaderNames().toList().filterNot(PlatformTracing::isInboundTraceHeader))
    }
}
