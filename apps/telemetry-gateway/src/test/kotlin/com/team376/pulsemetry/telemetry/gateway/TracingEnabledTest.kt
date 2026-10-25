package com.team376.pulsemetry.telemetry.gateway

import com.team376.pulsemetry.telemetry.gateway.support.AbstractGatewayIntegrationTest
import com.team376.pulsemetry.telemetry.gateway.support.GatewayTestKafka
import com.team376.pulsemetry.telemetry.gateway.support.TestSpans
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing
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
import java.time.Instant
import java.util.UUID

/**
 * 플랫폼 trace를 켠 Gateway(ADR 0069). Collector endpoint는 닿지 않는 주소다 — 내보내기 실패가 업무 응답을 바꾸지 않는다.
 * 설정이 달라 컨텍스트가 따로 뜬다.
 */
@AutoConfigureTracing
@Import(TestSpans.Config::class)
@TestPropertySource(
	properties = [
		"management.tracing.export.enabled=true",
		"management.tracing.sampling.probability=1.0",
		"management.opentelemetry.tracing.export.otlp.endpoint=http://127.0.0.1:9/v1/traces",
		"pulsemetry.tracing.environment=test",
		"pulsemetry.tracing.cluster=test-cluster",
		"pulsemetry.tracing.pod=telemetry-gateway-0",
	],
)
@ExtendWith(OutputCaptureExtension::class)
class TracingEnabledTest : AbstractGatewayIntegrationTest() {

	@LocalServerPort
	private var port: Int = 0

	@Value("\${local.management.port}")
	private var managementPort: Int = 0

	private val http: HttpClient = HttpClient.newHttpClient()

	@BeforeEach
	fun clear() = TestSpans.exporter.reset()

	private fun post(token: String?, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
		val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port/v1/logs"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString("""{"resourceLogs":[]}"""))
		token?.let { builder.header("Authorization", "Bearer $it") }
		headers.forEach { (name, value) -> builder.header(name, value) }
		return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
	}

	private fun token(tenantId: UUID = UUID.randomUUID()): String =
		GatewayTestKafka.KEY.issuer().issue(UUID.randomUUID(), tenantId, UUID.randomUUID(), Instant.now()).token

	@Test
	@DisplayName("Kafka record 에는 발행 span 문맥의 traceparent 하나만 실린다 — 요청 span 의 자식이고 신원·payload 속성은 없다")
	fun publishCarriesTraceparentOnly(output: CapturedOutput) {
		val tenantId = UUID.randomUUID()
		assertThat(post(token(tenantId)).statusCode()).isEqualTo(200)

		val record = GatewayTestKafka.records(GatewayTestKafka.shared.bootstrapServers).single { it.key().startsWith("$tenantId:") }
		assertThat(record.headers().map { it.key() }).containsExactly("traceparent")
		val traceparent = String(record.headers().lastHeader("traceparent").value(), Charsets.US_ASCII)
		assertThat(traceparent).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")
		// flags 의 sampled 비트(0x01)만 본다. OTel 은 W3C Level 2 의 random 비트(0x02)도 켠다.
		assertThat(traceparent.takeLast(2).toInt(16) and 1).isEqualTo(1)

		val spans = TestSpans.await { spans -> spans.any { it.kind == SpanKind.SERVER } && spans.any { it.kind == SpanKind.PRODUCER } }
		val server = spans.single { it.kind == SpanKind.SERVER }
		val producer = spans.single { it.kind == SpanKind.PRODUCER }
		assertThat(producer.parentSpanId).isEqualTo(server.spanId)
		assertThat(traceparent).startsWith("00-${producer.traceId}-${producer.spanId}-")
		assertThat(producer.attributes.get(AttributeKey.stringKey("messaging.destination.name"))).isEqualTo(GatewayTestKafka.TOPIC)
		assertThat(producer.attributes.get(AttributeKey.stringKey("pulsemetry.outcome"))).isEqualTo("acknowledged")
		assertThat(producer.attributes.asMap().keys.map { it.key }).doesNotContain("tenant_id", "installation_id", "authorization")
		assertThat(producer.attributes.asMap().values.map { it.toString() }).noneMatch { it.contains(tenantId.toString()) }

		// sampled 요청은 요청 span 안에서 완료 한 줄을 남긴다.
		val completed = output.all.lines().single { it.contains("event=platform_trace_completed stage=gateway") }
		assertThat(completed).contains("signal=logs status=200 outcome=accepted").contains("trace_id=${server.traceId} span_id=${server.spanId}")
	}

	@Test
	@DisplayName("토큰·본문·header 의 카나리아 문자열이 span 이름·속성·event 에 남지 않는다")
	fun canaryNeverInSpans() {
		val canary = "CANARYTR" + UUID.randomUUID().toString().replace("-", "")
		post("$canary.$canary.$canary")
		val good = token()
		val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port/v1/logs"))
			.header("Content-Type", "application/json").header("Authorization", "Bearer $good").header("X-Canary", canary)
			.POST(HttpRequest.BodyPublishers.ofString("""{"resourceLogs":[],"secret":"$canary"}"""))
		assertThat(http.send(builder.build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200)

		val spans = TestSpans.await { spans -> spans.count { it.kind == SpanKind.SERVER } >= 2 && spans.any { it.kind == SpanKind.PRODUCER } }
		val text = spans.flatMap { span -> span.attributes.asMap().values.map { it.toString() } + span.name + span.events.flatMap { e -> e.attributes.asMap().values.map { it.toString() } + e.name } }
		assertThat(text).noneMatch { it.contains(canary) || it.contains(good) }
	}

	@Test
	@DisplayName("공개 요청의 traceparent 는 부모가 되지 않는다 — 서버 span 은 새 root 이고 resource 는 고정 이름이다")
	fun inboundTraceparentIsIgnored() {
		val outside = "0af7651916cd43dd8448eb211c80319c"
		val response = post(token(), mapOf("traceparent" to "00-$outside-b7ad6b7169203331-01", "baggage" to "tenant=leak"))

		assertThat(response.statusCode()).isEqualTo(200)
		val spans = TestSpans.await { spans -> spans.any { it.kind == SpanKind.SERVER } }
		val server = spans.single { it.kind == SpanKind.SERVER }
		assertThat(server.traceId).isNotEqualTo(outside)
		assertThat(server.parentSpanContext.isValid).isFalse()
		assertThat(spans.map { it.traceId }).doesNotContain(outside)
		assertThat(spans.flatMap { it.attributes.asMap().values }.map { it.toString() }).noneMatch { it.contains("leak") }

		val resource = server.resource.attributes
		assertThat(resource.get(AttributeKey.stringKey("service.name"))).isEqualTo("telemetry-gateway")
		assertThat(resource.get(AttributeKey.stringKey("deployment.environment.name"))).isEqualTo("test")
		assertThat(resource.get(AttributeKey.stringKey("k8s.cluster.name"))).isEqualTo("test-cluster")
		assertThat(resource.get(AttributeKey.stringKey("k8s.pod.name"))).isEqualTo("telemetry-gateway-0")
	}

	@Test
	@DisplayName("health 요청은 span 을 만들지 않지만 http.server.requests 지표는 남는다")
	fun healthIsNotTracedButMeasured() {
		val health = http.send(
			HttpRequest.newBuilder(URI.create("http://localhost:$port/api/v1/healthz")).GET().build(),
			HttpResponse.BodyHandlers.ofString(),
		)
		assertThat(health.statusCode()).isEqualTo(200)
		assertThat(TestSpans.await(Duration.ofMillis(500)) { false }).isEmpty()
		val scrape = http.send(
			HttpRequest.newBuilder(URI.create("http://localhost:$managementPort/actuator/prometheus")).GET().build(),
			HttpResponse.BodyHandlers.ofString(),
		)
		assertThat(scrape.body()).containsPattern("""http_server_requests_seconds_count\{[^}]*uri="/api/v1/healthz"""")
	}

	@Test
	@DisplayName("span 안의 로그에만 trace_id·span_id 가 붙고 값이 없는 빈 필드는 찍지 않는다")
	fun logsCarryTraceIdsInsideSpans(output: CapturedOutput) {
		val response = post(null)
		assertThat(response.statusCode()).isEqualTo(401)
		val server = TestSpans.await { spans -> spans.any { it.kind == SpanKind.SERVER } }.single { it.kind == SpanKind.SERVER }

		val rejected = output.all.lines().single { it.contains("event=telemetry_gateway_rejected") }
		assertThat(rejected).contains("trace_id=${server.traceId} span_id=")
		assertThat(Regex("""trace_id=[0-9a-f]{32} span_id=[0-9a-f]{16} """).containsMatchIn(rejected)).isTrue()
		assertThat(output.all).doesNotContain("trace_id= span_id=")
	}
}
