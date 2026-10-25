package com.team376.pulsemetry.telemetry.consumer

import com.team376.pulsemetry.persistence.telemetry.TelemetrySinkUnavailableException
import com.team376.pulsemetry.telemetry.collector.VerifiedIngestContext
import com.team376.pulsemetry.telemetry.ingress.RawOtlpPayload
import com.team376.pulsemetry.telemetry.support.AbstractIngestIntegrationTest
import com.team376.pulsemetry.telemetry.support.IngestTestData
import com.team376.pulsemetry.telemetry.support.IngestTestKafka
import com.team376.pulsemetry.telemetry.support.TestSpans
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import org.apache.kafka.clients.producer.RecordMetadata
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.doAnswer
import org.springframework.beans.factory.annotation.Autowired
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
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * 플랫폼 trace 를 켠 ingest Consumer(ADR 0069). 실제 Kafka·PostgreSQL·ClickHouse 위에서 record header 의 문맥이 처리 span·로그로 이어지는지 본다.
 * Collector endpoint 는 닿지 않는 주소다 — 내보내기 실패가 처리·commit 을 바꾸지 않는다. 설정이 달라 컨텍스트가 따로 뜬다.
 */
@AutoConfigureTracing
@Import(TestSpans.Config::class)
@TestPropertySource(
	properties = [
		"management.tracing.export.enabled=true",
		"management.tracing.sampling.probability=1.0",
		"management.opentelemetry.tracing.export.otlp.endpoint=http://127.0.0.1:9/v1/traces",
	],
)
@ExtendWith(OutputCaptureExtension::class)
class ConsumerTracingTest : AbstractIngestIntegrationTest() {

	@Autowired
	private lateinit var data: IngestTestData

	@LocalServerPort
	private var port: Int = 0

	@Value("\${local.management.port}")
	private var managementPort: Int = 0

	@BeforeEach
	fun reset() {
		data.clear()
		TestSpans.exporter.reset()
	}

	@AfterEach
	fun cleanUp() = data.clear()

	@Test
	@DisplayName("record header 의 traceparent 가 처리 span 의 부모가 되고 결과가 completed 다 — ClickHouse 쓰기는 그 자식 client span 이다")
	fun recordJoinsGatewayTrace() {
		val message = IngestTestKafka.accepted(data.seedJwt(), smokeLog(Instant.now().truncatedTo(ChronoUnit.MICROS)))
		awaitCommitted(IngestTestKafka.publish(message, listOf("traceparent" to TRACEPARENT.toByteArray())))

		val spans = TestSpans.await { spans -> spans.any { it.kind == SpanKind.CONSUMER } }
		val consumer = spans.single { it.kind == SpanKind.CONSUMER }
		val clickhouse = spans.filter { it.kind == SpanKind.CLIENT && it.traceId == TRACE_ID }
		assertThat(clickhouse.map { it.name }).contains("clickhouse insert telemetry_events")
		assertThat(clickhouse).allMatch { it.parentSpanId == consumer.spanId }
		assertThat(clickhouse.flatMap { it.attributes.asMap().values }.map { it.toString() }).noneMatch { it.contains("INSERT") || it.contains("query=") }
		assertThat(consumer.traceId).isEqualTo(TRACE_ID)
		assertThat(consumer.parentSpanId).isEqualTo(PARENT_ID)
		assertThat(consumer.attributes.get(AttributeKey.stringKey("pulsemetry.outcome"))).isEqualTo("completed")
		assertThat(consumer.status.statusCode).isNotEqualTo(StatusCode.ERROR)
		assertThat(consumer.attributes.get(AttributeKey.stringKey("pulsemetry.message_id"))).isEqualTo(message.messageId.toString())
		assertThat(consumer.resource.attributes.get(AttributeKey.stringKey("service.name"))).isEqualTo("telemetry-ingest")
	}

	@Test
	@DisplayName("재시도마다 새 span 이고 재시도 소진 로그는 같은 trace 에 남는다 — 다음 record 의 로그로 문맥이 새지 않는다")
	fun retriesAndExhaustionStayInTheRecordTrace(output: CapturedOutput) {
		val seeded = data.seedJwt()
		val failing = IngestTestKafka.accepted(seeded, LOG_BODY)
		failProcessing(failing.messageId)
		val failingAt = IngestTestKafka.publish(failing, listOf("traceparent" to TRACEPARENT.toByteArray()))
		awaitCommitted(failingAt)
		// header 없는 잘못된 envelope. 자기 root 로 처리되고 앞 record 의 trace 를 물려받지 않는다.
		val garbageAt = IngestTestKafka.send("garbage", "not an envelope".toByteArray())
		awaitCommitted(garbageAt)

		val spans = TestSpans.await { spans -> spans.count { it.kind == SpanKind.CONSUMER } >= 6 }
		val attempts = spans.filter { it.traceId == TRACE_ID && it.name == "telemetry process" }
		assertThat(attempts).hasSize(4)
		assertThat(attempts.map { it.spanId }.toSet()).hasSize(4)
		assertThat(attempts.map { it.parentSpanId }.toSet()).containsExactly(PARENT_ID)
		assertThat(attempts.map { it.attributes.get(AttributeKey.stringKey("pulsemetry.outcome")) }.toSet()).containsExactly("transient_failed")
		assertThat(attempts.map { it.attributes.get(AttributeKey.stringKey("error.type")) }.toSet()).containsExactly("TelemetrySinkUnavailableException")
		val recover = spans.single { it.name == "telemetry recover" }
		assertThat(recover.traceId).isEqualTo(TRACE_ID)
		assertThat(recover.attributes.get(AttributeKey.stringKey("pulsemetry.outcome"))).isEqualTo("retry_exhausted")
		assertThat(recover.attributes.get(AttributeKey.stringKey("error.type"))).isEqualTo("retry_exhausted")
		// 실패 시도·재시도 소진·영구 거부는 모두 ERROR status 이고 설명은 비어 있다 — Tempo 의 status = error 검색에 잡힌다.
		val rejected = spans.single { it.name == "telemetry process" && it.traceId != TRACE_ID }
		assertThat(rejected.attributes.get(AttributeKey.stringKey("pulsemetry.outcome"))).isEqualTo("permanent_rejected")
		assertThat(rejected.attributes.get(AttributeKey.stringKey("error.type"))).isNotBlank()
		for (failed in attempts + recover + rejected) {
			assertThat(failed.status.statusCode).isEqualTo(StatusCode.ERROR)
			assertThat(failed.status.description).isEmpty()
		}

		val exhausted = output.all.lines().single { it.contains("event=telemetry_retry_exhausted messageId=${failing.messageId}") }
		assertThat(exhausted).contains("trace_id=$TRACE_ID span_id=${recover.spanId}")
		val transient = output.all.lines().filter { it.contains("event=telemetry_transient_failure messageId=${failing.messageId}") }
		assertThat(transient).hasSize(4).allMatch { it.contains("trace_id=$TRACE_ID") }
		// sampled 시도마다 완료 한 줄을 그 시도의 span 안에서 남긴다.
		val completed = output.all.lines().filter { it.contains("event=platform_trace_completed stage=ingest") && it.contains("trace_id=$TRACE_ID") }
		assertThat(completed).hasSize(4).allMatch { it.contains("outcome=transient_failed") }
		assertThat(completed.map { Regex("span_id=([0-9a-f]{16})").find(it)!!.groupValues[1] }).containsExactlyInAnyOrderElementsOf(attempts.map { it.spanId })

		val garbage = output.all.lines().single { it.contains("event=telemetry_permanent_failure") && it.contains("offset=${garbageAt.offset()}") }
		assertThat(Regex("""trace_id=([0-9a-f]{32})""").find(garbage)!!.groupValues[1]).isNotEqualTo(TRACE_ID)
		assertThat(output.all).doesNotContain("trace_id= span_id=")
		// 원본 예외 메시지는 span 에도 없다.
		assertThat(spans.flatMap { it.attributes.asMap().values }.map { it.toString() }).noneMatch { it.contains("simulated clickhouse outage") }
		// 이벤트는 ERROR 표식뿐이다 — 메시지 속성과 stack frame 이 없다.
		val events = spans.flatMap { it.events }
		assertThat(events).isNotEmpty().allMatch { it.name == "exception" }
		assertThat(events.flatMap { it.attributes.asMap().values }.map { it.toString() }).noneMatch { it.contains("simulated clickhouse outage") || it.contains("\tat ") }
		assertThat(events.map { it.attributes.get(AttributeKey.stringKey("exception.message")) }).containsOnlyNulls()
	}

	@Test
	@DisplayName("trace 를 켜도 health 요청은 서버 span 을 만들지 않고 http.server.requests 지표는 남는다")
	fun healthIsNotTracedButMeasured() {
		val http = HttpClient.newHttpClient()
		val health = http.send(HttpRequest.newBuilder(URI.create("http://localhost:$port/api/v1/healthz")).GET().build(), HttpResponse.BodyHandlers.ofString())
		assertThat(health.statusCode()).isEqualTo(200)
		assertThat(TestSpans.await(Duration.ofMillis(500)) { false }.filter { it.kind == SpanKind.SERVER }).isEmpty()
		val scrape = http.send(HttpRequest.newBuilder(URI.create("http://localhost:$managementPort/actuator/prometheus")).GET().build(), HttpResponse.BodyHandlers.ofString())
		assertThat(scrape.body()).containsPattern("""http_server_requests_seconds_count\{[^}]*uri="/api/v1/healthz"""")
	}

	/** [messageId] 의 처리만 계속 일시 실패시킨다. 나머지는 실제 수집 코어를 지난다. */
	private fun failProcessing(messageId: UUID) {
		val attempts = AtomicInteger()
		doAnswer { invocation ->
			val context = invocation.getArgument<VerifiedIngestContext>(1)
			if (context.receiptId == messageId) {
				attempts.incrementAndGet()
				throw TelemetrySinkUnavailableException("simulated clickhouse outage")
			}
			invocation.callRealMethod()
		}.`when`(otlpProcessor).process(anything<RawOtlpPayload>(), anything<VerifiedIngestContext>())
	}

	private fun awaitCommitted(metadata: RecordMetadata, timeout: Duration = Duration.ofSeconds(30)) {
		val until = System.nanoTime() + timeout.toNanos()
		while (System.nanoTime() < until) {
			if (IngestTestKafka.committed(metadata.partition()) >= metadata.offset() + 1) return
			Thread.sleep(100)
		}
		throw AssertionError("offset ${metadata.partition()}@${metadata.offset()} 가 $timeout 안에 확정되지 않았다")
	}

	/** 독립 smoke 와 같은 Claude Code api_request 원문. 시각만 [at] 으로 바꾼다(TelemetryKafkaConsumerTest 와 같은 자료). */
	private fun smokeLog(at: Instant): ByteArray {
		val template = javaClass.getResourceAsStream("/observability-smoke-log.json")!!.bufferedReader().use { it.readText() }
		return template.replace("2026-10-21T15:00:00+00:00", at.toString())
			.replace("1792594800000000000", (at.epochSecond * 1_000_000_000 + at.nano).toString()).toByteArray()
	}

	@Suppress("UNCHECKED_CAST")
	private fun <T> anything(): T {
		ArgumentMatchers.any<T>()
		return null as T
	}

	private companion object {
		const val TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"
		const val PARENT_ID = "00f067aa0ba902b7"
		const val TRACEPARENT = "00-$TRACE_ID-$PARENT_ID-01"
		val LOG_BODY: ByteArray = """{"resourceLogs":[]}""".toByteArray()
	}
}
