package com.team376.pulsemetry.enrollment.config

import com.team376.pulsemetry.telemetry.observability.PlatformTracing
import io.micrometer.common.KeyValues
import io.micrometer.observation.Observation
import io.micrometer.tracing.Tracer
import io.micrometer.tracing.handler.TracingObservationHandler
import io.micrometer.tracing.propagation.Propagator
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.data.DelegatingSpanData
import io.opentelemetry.sdk.trace.data.EventData
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.data.StatusData
import io.opentelemetry.sdk.trace.export.SpanExporter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.SdkTracerProviderBuilderCustomizer
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.SpanExporters
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.core.env.Environment
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention
import org.springframework.http.server.observation.ServerRequestObservationContext
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.filter.ServerHttpObservationFilter
import java.util.Collections
import java.util.Enumeration

/**
 * 플랫폼 trace 조립(ADR 0069·0079). 기본은 꺼져 있다(`PULSEMETRY_TRACING_ENABLED`). 켜면 OTLP/HTTP로 Collector에 보낸다.
 * telemetry-ingest 의 `TracingConfig` 와 같은 규칙이다 — 규칙을 바꾸면 네 앱을 함께 고친다(ADR 0079 Negative).
 *
 * - resource의 `service.name`은 `enrollment-api`로 고정하고 Kubernetes 값은 있을 때만 붙인다.
 * - 내보내는 span에서 예외 메시지·stack과 status 설명을 지운다([ScrubbedSpanExporter]).
 * - 서버 span 에 요청 경로(`http.url`)를 싣지 않는다 — 경로에 조직·설치 ID 가 있다([PlatformServerRequestObservationConvention]).
 * - health·관리 경로는 span을 만들지 않는다. observation은 남겨 `http.server.requests` 지표는 tracing on/off와 관계없이 그대로다.
 *   Spring Security observation은 `application.yaml`에서 끈다.
 * - 공개 요청의 trace header는 서버 observation보다 먼저 지운다 — 요청은 언제나 새 플랫폼 root다.
 * - sampled 요청은 서버 span 안에서 완료 한 줄을 남긴다([TraceCompletionFilter]).
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
        builder.addResource(platformResource(environment, PlatformTracing.ENROLLMENT_SERVICE))
    }

    /**
     * 내보내는 모든 span 에서 예외 원문을 지운다. Boot 의 서버 observation 은 미처리 예외를 `span.error(예외)`로 넘겨
     * 메시지를 status 설명에, 메시지·stack 을 exception 이벤트에 싣는다 — Collector 의 attributes 처리기는 이벤트를 보지 않는다.
     * Boot 의 기본 `SpanExporters`(@ConditionalOnMissingBean)를 대신해 등록된 exporter 를 모두 감싼다.
     */
    @Bean
    fun platformSpanExporters(exporters: ObjectProvider<SpanExporter>): SpanExporters =
        SpanExporters.of(exporters.orderedStream().map { ScrubbedSpanExporter(it) }.toList())

    /**
     * health·관리 경로의 span만 뺀다. ObservationPredicate로 막으면 observation 자체가 없어져 지표까지 사라진다.
     * tracing handler 묶음은 먼저 맞는 handler 하나만 쓰므로(Receiver 1000·Sender 2000·Default 마지막), 그보다 앞에 둔다.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    fun untracedServerPaths(): UntracedServerPathHandler = UntracedServerPathHandler()

    /** 서버 observation filter(`HIGHEST_PRECEDENCE + 1`)와 Security 체인보다 앞에 둔다. */
    @Bean
    fun inboundTraceHeaderFilter(): FilterRegistrationBean<InboundTraceHeaderFilter> =
        FilterRegistrationBean(InboundTraceHeaderFilter()).apply { order = Ordered.HIGHEST_PRECEDENCE }

    /** Boot 의 서버 observation filter 가 이 convention 을 쓴다(사용자 정의 bean 우선). 지표 이름·label 은 기본과 같다. */
    @Bean
    fun platformServerRequestConvention(): PlatformServerRequestObservationConvention = PlatformServerRequestObservationConvention()

    /** 서버 observation filter(`HIGHEST_PRECEDENCE + 1`) 안쪽이다 — 체인이 돌아왔을 때 서버 span 이 아직 열려 있다. */
    @Bean
    fun traceCompletionFilter(tracer: Tracer, convention: PlatformServerRequestObservationConvention): FilterRegistrationBean<TraceCompletionFilter> =
        FilterRegistrationBean(TraceCompletionFilter(tracer, convention, STAGE)).apply { order = Ordered.HIGHEST_PRECEDENCE + 2 }

    private companion object {
        const val STAGE = "enrollment"
    }
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

/**
 * exception 이벤트의 메시지·stack 속성과 status 설명을 지우고 [delegate] 로 넘긴다(ADR 0069).
 * 이벤트 이름·`exception.type`·다른 속성과 status code 는 둔다 — ERROR 검색과 분류는 그대로다.
 */
class ScrubbedSpanExporter(private val delegate: SpanExporter) : SpanExporter by delegate {

    override fun export(spans: Collection<SpanData>): CompletableResultCode = delegate.export(spans.map(::scrub))

    internal companion object {
        private val DROPPED = setOf("exception.message", "exception.stacktrace")

        private fun EventData.dirty(): Boolean = attributes.asMap().keys.any { it.key in DROPPED }

        fun scrub(span: SpanData): SpanData {
            if (span.status.description.isEmpty() && span.events.none { it.dirty() }) return span
            val status = StatusData.create(span.status.statusCode, "")
            val events = span.events.map { event ->
                if (!event.dirty()) event
                else EventData.create(event.epochNanos, event.name, event.attributes.toBuilder().removeIf { it.key in DROPPED }.build(), event.totalAttributeCount)
            }
            return object : DelegatingSpanData(span) {
                override fun getStatus(): StatusData = status

                override fun getEvents(): List<EventData> = events
            }
        }
    }
}

/**
 * 서버 요청 observation 의 이름·label 은 기본 그대로 두고 high-cardinality `http.url`(요청 경로 원문)만 뺀다(ADR 0079).
 * span 이름과 `uri` label 은 경로 템플릿이라 조직 ID 가 들어가지 않는다.
 */
class PlatformServerRequestObservationConvention : DefaultServerRequestObservationConvention() {

    override fun getHighCardinalityKeyValues(context: ServerRequestObservationContext): KeyValues = KeyValues.empty()

    /** 지표의 `uri` label 과 같은 값 — 경로 템플릿이거나 `NOT_FOUND`·`REDIRECTION`·`UNKNOWN` 같은 고정 값이다. */
    fun route(context: ServerRequestObservationContext): String = uri(context).value
}

/**
 * health·관리 경로의 서버 요청을 맡아 span을 만들지 않는 tracing handler다. 다른 handler는 이 요청을 보지 않는다.
 * 지표 handler(TracingAwareMeterObservationHandler)는 TracingContext를 반드시 꺼내므로 span 없는 빈 문맥만 넣는다.
 */
class UntracedServerPathHandler : TracingObservationHandler<ServerRequestObservationContext> {

    override fun supportsContext(context: Observation.Context): Boolean =
        context is ServerRequestObservationContext && context.carrier?.requestURI?.let(PlatformTracing::tracesServerPath) == false

    override fun onStart(context: ServerRequestObservationContext) {
        getTracingContext(context)
    }

    override fun onStop(context: ServerRequestObservationContext) = Unit

    override fun onError(context: ServerRequestObservationContext) = Unit

    override fun onEvent(event: Observation.Event, context: ServerRequestObservationContext) = Unit

    override fun onScopeOpened(context: ServerRequestObservationContext) = Unit

    override fun onScopeClosed(context: ServerRequestObservationContext) = Unit

    override fun onScopeReset(context: ServerRequestObservationContext) = Unit

    override fun getTracer(): Tracer = Tracer.NOOP
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

/**
 * sampled 요청의 완료 한 줄(ADR 0079): `event=platform_trace_completed stage method route status outcome`.
 * 로그 → trace 이동의 기준점이다. route 는 경로 템플릿이고 실제 ID·query 는 싣지 않는다. 로그 실패가 응답을 바꾸지 않는다.
 */
class TraceCompletionFilter(
    private val tracer: Tracer,
    private val convention: PlatformServerRequestObservationConvention,
    private val stage: String,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(TraceCompletionFilter::class.java)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !PlatformTracing.tracesServerPath(request.requestURI)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        var failed = false
        try {
            chain.doFilter(request, response)
        } catch (failure: Throwable) {
            failed = true
            throw failure
        } finally {
            completed(request, response, failed)
        }
    }

    private fun completed(request: HttpServletRequest, response: HttpServletResponse, failed: Boolean) {
        runCatching {
            if (tracer.currentSpan()?.context()?.sampled() != true) return
            // 처리되지 않은 예외는 컨테이너가 뒤에서 500 으로 바꾼다. 지금의 응답 상태는 아직 그 값이 아니다.
            val status = if (failed) HttpServletResponse.SC_INTERNAL_SERVER_ERROR else response.status
            val route = ServerHttpObservationFilter.findObservationContext(request).map(convention::route).orElse("UNKNOWN")
            log.info("event=platform_trace_completed stage={} method={} route={} status={} outcome={}",
                stage, method(request.method), route.replace(WHITESPACE, "_"), status, outcome(status))
        }
    }

    private companion object {
        val METHOD = Regex("[A-Z]{1,16}")
        val WHITESPACE = Regex("\\s")

        fun method(value: String?): String = value?.takeIf { METHOD.matches(it) } ?: "OTHER"

        fun outcome(status: Int): String = when (status / 100) {
            1 -> "informational"
            2 -> "success"
            3 -> "redirection"
            4 -> "client_error"
            5 -> "server_error"
            else -> "unknown"
        }
    }
}
