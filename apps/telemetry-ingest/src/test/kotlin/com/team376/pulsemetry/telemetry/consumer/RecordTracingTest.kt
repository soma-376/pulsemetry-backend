package com.team376.pulsemetry.telemetry.consumer

import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext
import io.micrometer.tracing.otel.bridge.OtelPropagator
import io.micrometer.tracing.otel.bridge.OtelTracer
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.samplers.Sampler
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.record.TimestampType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.Optional

/** record header 로 처리 span 의 부모를 고르는 규칙(ADR 0069). 실제 OTel SDK 위의 Micrometer bridge 로 본다. */
class RecordTracingTest {

    private val exporter = InMemorySpanExporter.create()
    private val provider = SdkTracerProvider.builder().setSampler(Sampler.alwaysOn()).addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()
    private val otel = provider.get("test")
    private val tracing = RecordTracing(
        OtelTracer(otel, OtelCurrentTraceContext()) { },
        OtelPropagator(ContextPropagators.create(W3CTraceContextPropagator.getInstance()), otel),
    )

    @AfterEach
    fun close() = provider.close()

    private fun record(vararg traceparents: ByteArray, offset: Long = 7): ConsumerRecord<ByteArray, ByteArray> {
        val headers = RecordHeaders(traceparents.map { RecordHeader("traceparent", it) })
        return ConsumerRecord("ingress", 2, offset, -1L, TimestampType.CREATE_TIME, 0, 0, ByteArray(0), ByteArray(0), headers, Optional.empty())
    }

    private fun finish(record: ConsumerRecord<ByteArray, ByteArray>, attempt: Int = 1): SpanData {
        tracing.start(record, "telemetry process", attempt).end()
        return exporter.finishedSpanItems.last()
    }

    @Test
    fun validHeaderBecomesParent() {
        val span = finish(record(VALID.toByteArray()))
        assertThat(span.kind).isEqualTo(SpanKind.CONSUMER)
        assertThat(span.traceId).isEqualTo(TRACE_ID)
        assertThat(span.parentSpanId).isEqualTo(PARENT_ID)
        assertThat(span.attributes.get(AttributeKey.stringKey("messaging.destination.name"))).isEqualTo("ingress")
        assertThat(span.attributes.get(AttributeKey.stringKey("messaging.destination.partition.id"))).isEqualTo("2")
        assertThat(span.attributes.get(AttributeKey.stringKey("messaging.kafka.offset"))).isEqualTo("7")
        assertThat(span.attributes.get(AttributeKey.stringKey("pulsemetry.attempt"))).isEqualTo("1")
    }

    @Test
    fun missingDuplicateOversizedOrMalformedHeaderStartsNewRoot() {
        listOf(
            record(),
            record(VALID.toByteArray(), VALID.toByteArray()),
            record((VALID + "-00").toByteArray()),
            record(ByteArray(64 * 1024) { 'a'.code.toByte() }),
            record("00-zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz-b7ad6b7169203331-01".toByteArray()),
            record("00-00000000000000000000000000000000-b7ad6b7169203331-01".toByteArray()),
        ).forEach { record ->
            val span = finish(record)
            assertThat(span.parentSpanContext.isValid).describedAs(record.headers().toString()).isFalse()
            assertThat(span.traceId).isNotEqualTo(TRACE_ID)
        }
    }

    @Test
    fun everyAttemptIsANewSpanUnderTheSameParent() {
        val spans = (1..4).map { finish(record(VALID.toByteArray()), it) }
        assertThat(spans.map { it.spanId }.toSet()).hasSize(4)
        assertThat(spans.map { it.parentSpanId }.toSet()).containsExactly(PARENT_ID)
        assertThat(spans.map { it.attributes.get(AttributeKey.stringKey("pulsemetry.attempt")) }).containsExactly("1", "2", "3", "4")
    }

    @Test
    fun aroundJoinsTheRecordTraceAndClosesTheScope() {
        var currentInside: String? = null
        tracing.around(record(VALID.toByteArray()), "telemetry recover", "retry_exhausted", "process") {
            currentInside = io.opentelemetry.api.trace.Span.current().spanContext.traceId
        }
        val span = exporter.finishedSpanItems.single()
        assertThat(currentInside).isEqualTo(TRACE_ID)
        assertThat(span.parentSpanId).isEqualTo(PARENT_ID)
        assertThat(span.attributes.get(AttributeKey.stringKey("pulsemetry.outcome"))).isEqualTo("retry_exhausted")
        assertThat(span.attributes.get(AttributeKey.stringKey("pulsemetry.stage"))).isEqualTo("process")
        // 다음 record 로 문맥이 새지 않는다.
        assertThat(io.opentelemetry.api.trace.Span.current().spanContext.isValid).isFalse()
    }

    @Test
    fun noopTracingNeverTouchesTheRecord() {
        val noop = RecordTracing()
        val record = record(VALID.toByteArray())
        noop.around(record, "telemetry recover", "retry_exhausted", "process") { }
        noop.start(record, "telemetry process", 1).end()
        assertThat(record.headers().headers("traceparent")).hasSize(1)
        assertThat(exporter.finishedSpanItems).isEmpty()
    }

    private companion object {
        const val TRACE_ID = "0af7651916cd43dd8448eb211c80319c"
        const val PARENT_ID = "b7ad6b7169203331"
        const val VALID = "00-$TRACE_ID-$PARENT_ID-01"
    }
}
