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
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import java.time.Duration
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

	@BeforeEach
	fun reset() {
		data.clear()
		TestSpans.exporter.reset()
	}

	@AfterEach
	fun cleanUp() = data.clear()

	@Test
	@DisplayName("record header 의 traceparent 가 처리 span 의 부모가 되고 결과가 completed 다")
	fun recordJoinsGatewayTrace() {
		val message = IngestTestKafka.accepted(data.seedJwt(), LOG_BODY)
		awaitCommitted(IngestTestKafka.publish(message, listOf("traceparent" to TRACEPARENT.toByteArray())))

		val consumer = TestSpans.await { spans -> spans.any { it.kind == SpanKind.CONSUMER } }.single { it.kind == SpanKind.CONSUMER }
		assertThat(consumer.traceId).isEqualTo(TRACE_ID)
		assertThat(consumer.parentSpanId).isEqualTo(PARENT_ID)
		assertThat(consumer.attributes.get(AttributeKey.stringKey("pulsemetry.outcome"))).isEqualTo("completed")
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
		assertThat(attempts.map { it.attributes.get(AttributeKey.stringKey("error")) }.toSet()).containsExactly("TelemetrySinkUnavailableException")
		val recover = spans.single { it.name == "telemetry recover" }
		assertThat(recover.traceId).isEqualTo(TRACE_ID)
		assertThat(recover.attributes.get(AttributeKey.stringKey("pulsemetry.outcome"))).isEqualTo("retry_exhausted")

		val exhausted = output.all.lines().single { it.contains("event=telemetry_retry_exhausted messageId=${failing.messageId}") }
		assertThat(exhausted).contains("trace_id=$TRACE_ID span_id=${recover.spanId}")
		val transient = output.all.lines().filter { it.contains("event=telemetry_transient_failure messageId=${failing.messageId}") }
		assertThat(transient).hasSize(4).allMatch { it.contains("trace_id=$TRACE_ID") }

		val garbage = output.all.lines().single { it.contains("event=telemetry_permanent_failure") && it.contains("offset=${garbageAt.offset()}") }
		assertThat(Regex("""trace_id=([0-9a-f]{32})""").find(garbage)!!.groupValues[1]).isNotEqualTo(TRACE_ID)
		assertThat(output.all).doesNotContain("trace_id= span_id=")
		// 원본 예외 메시지는 span 에도 없다.
		assertThat(spans.flatMap { it.attributes.asMap().values }.map { it.toString() }).noneMatch { it.contains("simulated clickhouse outage") }
		assertThat(spans.flatMap { it.events }).isEmpty()
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
