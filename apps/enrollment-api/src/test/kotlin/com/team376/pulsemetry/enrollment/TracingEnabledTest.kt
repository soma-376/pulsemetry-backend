package com.team376.pulsemetry.enrollment

import com.team376.pulsemetry.enrollment.support.TestSpans
import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.sdk.trace.data.SpanData
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * 플랫폼 trace 를 켠 enrollment-api(ADR 0079). Collector endpoint 는 닿지 않는 주소다 — 내보내기 실패가 응답을 바꾸지 않는다.
 * 설정이 달라 컨텍스트가 따로 뜬다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTracing
@Import(PostgresContainerConfig::class, TestSpans.Config::class)
@TestPropertySource(
    properties = [
        "management.tracing.export.enabled=true",
        "management.tracing.sampling.probability=1.0",
        "management.opentelemetry.tracing.export.otlp.endpoint=http://127.0.0.1:9/v1/traces",
        "pulsemetry.tracing.environment=test",
        "pulsemetry.tracing.cluster=test-cluster",
        "pulsemetry.tracing.pod=enrollment-api-0",
    ],
)
@ExtendWith(OutputCaptureExtension::class)
class TracingEnabledTest {

    @LocalServerPort
    private var port: Int = 0

    @Value("\${local.management.port}")
    private var managementPort: Int = 0

    private val http: HttpClient = HttpClient.newHttpClient()

    @BeforeEach
    fun clear() = TestSpans.exporter.reset()

    private fun get(path: String, headers: Map<String, String> = emptyMap(), port: Int = this.port): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path")).GET()
        headers.forEach { (name, value) -> builder.header(name, value) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun SpanData.text(): List<String> =
        attributes.asMap().values.map { it.toString() } + name + status.description +
            events.flatMap { e -> e.attributes.asMap().values.map { it.toString() } + e.name }

    private fun serverSpan(): SpanData =
        TestSpans.await { spans -> spans.any { it.kind == SpanKind.SERVER } }.single { it.kind == SpanKind.SERVER }

    @Test
    @DisplayName("서버 span 과 완료 로그는 경로 템플릿만 갖고 경로의 원문 값은 어디에도 없다")
    fun pathValuesNeverLeaveTheApp(output: CapturedOutput) {
        val canary = "CANARY" + UUID.randomUUID().toString().replace("-", "")
        assertThat(get("/bin/$canary").statusCode()).isEqualTo(404)

        val server = serverSpan()
        assertThat(server.name).isEqualTo("http get /bin/{filename}")
        assertThat(server.attributes.asMap().keys.map { it.key }).doesNotContain("http.url")
        assertThat(server.text()).noneMatch { it.contains(canary) }

        // sampled 요청은 서버 span 안에서 완료 한 줄을 남긴다.
        val completed = output.all.lines().single { it.contains("event=platform_trace_completed stage=enrollment") }
        assertThat(completed)
            .contains("method=GET route=/bin/{filename} status=404 outcome=client_error")
            .contains("trace_id=${server.traceId} span_id=${server.spanId}")
            .doesNotContain(canary)
    }

    @Test
    @DisplayName("공개 요청의 traceparent 는 부모가 되지 않는다 — 서버 span 은 새 root 이고 resource 는 고정 이름이다")
    fun inboundTraceparentIsIgnored() {
        val outside = "0af7651916cd43dd8448eb211c80319c"
        get("/bin/pulsemetry", headers = mapOf("traceparent" to "00-$outside-b7ad6b7169203331-01", "baggage" to "tenant=leak"))

        val server = serverSpan()
        assertThat(server.traceId).isNotEqualTo(outside)
        assertThat(server.parentSpanContext.isValid).isFalse()
        assertThat(server.text()).noneMatch { it.contains("leak") }

        val resource = server.resource.attributes
        assertThat(resource.get(AttributeKey.stringKey("service.name"))).isEqualTo("enrollment-api")
        assertThat(resource.get(AttributeKey.stringKey("deployment.environment.name"))).isEqualTo("test")
        assertThat(resource.get(AttributeKey.stringKey("k8s.cluster.name"))).isEqualTo("test-cluster")
        assertThat(resource.get(AttributeKey.stringKey("k8s.pod.name"))).isEqualTo("enrollment-api-0")
    }

    @Test
    @DisplayName("health 요청은 span 을 만들지 않지만 http.server.requests 지표는 남는다")
    fun healthIsNotTracedButMeasured() {
        assertThat(get("/api/v1/healthz").statusCode()).isEqualTo(200)
        assertThat(TestSpans.await(Duration.ofMillis(500)) { false }.filter { it.kind == SpanKind.SERVER }).isEmpty()
        assertThat(get("/actuator/prometheus", port = managementPort).body())
            .containsPattern("""http_server_requests_seconds_count\{[^}]*uri="/api/v1/healthz"""")
    }

    @Test
    @DisplayName("span 이 없는 로그에는 빈 trace 필드를 찍지 않는다")
    fun noEmptyTraceFields(output: CapturedOutput) {
        get("/api/v1/healthz")
        get("/bin/pulsemetry")
        serverSpan()
        assertThat(output.all).doesNotContain("trace_id= span_id=")
    }
}
