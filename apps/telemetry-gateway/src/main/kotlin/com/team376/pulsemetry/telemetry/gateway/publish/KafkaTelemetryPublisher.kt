package com.team376.pulsemetry.telemetry.gateway.publish

import com.team376.pulsemetry.telemetry.ingress.AcceptedTelemetry
import com.team376.pulsemetry.telemetry.ingress.TelemetryEnvelopeCodec
import com.team376.pulsemetry.telemetry.ingress.TelemetryLogEvent
import com.team376.pulsemetry.telemetry.ingress.TelemetryRecordKey
import com.team376.pulsemetry.telemetry.observability.TelemetryMetric
import com.team376.pulsemetry.telemetry.observability.TelemetryObserver
import com.team376.pulsemetry.telemetry.observability.observe
import com.team376.pulsemetry.telemetry.observability.PlatformTracing
import io.micrometer.tracing.Span
import io.micrometer.tracing.Tracer
import io.micrometer.tracing.propagation.Propagator
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.errors.AuthenticationException
import org.apache.kafka.common.errors.AuthorizationException
import org.apache.kafka.common.errors.NotEnoughReplicasAfterAppendException
import org.apache.kafka.common.errors.NotEnoughReplicasException
import org.apache.kafka.common.errors.RecordTooLargeException
import org.apache.kafka.common.errors.TimeoutException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * kafka-clients producer 로 envelope 를 발행한다. key 는 `tenant_id:installation_id`, 값은 envelope v1 바이트다.
 * Bearer·신원을 header 로 실어 나르지 않는다(ADR 0066 Decision 4). header 는 플랫폼 trace 를 켰을 때의 `traceparent` 하나뿐이다
 * (ADR 0069) — 발행 span 의 문맥이고, trace 를 끄면(기본) [Tracer.NOOP] 이라 header 가 없다.
 *
 * producer 는 `acks=all`·idempotence 이고 `max.block.ms`·`delivery.timeout.ms` 가 시간을 묶는다(`GatewayConfig`).
 * 여기서는 그 위에 요청의 단일 기한을 한 번 더 건다 — `send()` 가 돌아온 뒤 남은 시간만큼만 ack 를 기다린다.
 *
 * 로그·예외에 record 의 값을 싣지 않는다. Spring 의 `KafkaTemplate` 을 쓰지 않는 이유이기도 하다 — 그 기본 실패 리스너는 payload
 * 앞부분을 로그에 남긴다.
 *
 * [close] 는 남은 발행을 [closeTimeout] 까지 기다린다. Spring 이 웹 서버의 graceful 종료(진행 중 요청 정리)를 마친 뒤 부른다.
 */
class KafkaTelemetryPublisher(
	private val producer: Producer<String, ByteArray>,
	private val topic: String,
	private val clock: Clock,
	private val closeTimeout: Duration,
	private val observer: TelemetryObserver = TelemetryObserver.NONE,
	private val clientMetrics: AutoCloseable? = null,
	private val tracer: Tracer = Tracer.NOOP,
	private val propagator: Propagator = Propagator.NOOP,
) : TelemetryPublisher, AutoCloseable {

	override fun publish(message: AcceptedTelemetry, deadline: Instant): PublishedRecord {
		val started = System.nanoTime()
		var outcome = "failed"
		var reason = "broker_error"
		val signal = message.payload.signal.name.lowercase()
		val span = publishSpan(signal, message)
		try {
			return tracer.withSpan(span).use { send(message, deadline, span) }.also { outcome = "acknowledged"; reason = "none" }
		} catch (failure: TelemetryPublishException) {
			reason = failure.reason
			throw failure
		} finally {
			// 실패는 reason 코드로만 분류하고 ERROR 로 표시한다. 원본 예외는 span 에 넘기지 않는다(ADR 0069).
			if (outcome != "acknowledged") runCatching { span.tag(PlatformTracing.ERROR_TYPE, reason).error(PlatformTracing.SPAN_FAILURE) }
			span.tag("pulsemetry.outcome", outcome).tag("pulsemetry.reason", reason).end()
			observer.observe(TelemetryMetric.GATEWAY_PUBLISH, 1.0, "signal" to signal, "outcome" to outcome, "reason" to reason)
			observer.observe(TelemetryMetric.GATEWAY_PUBLISH_DURATION, (System.nanoTime() - started) / 1e9, "signal" to signal, "outcome" to outcome)
		}
	}

	/** 요청 span(현재 문맥)의 자식인 producer span. 속성은 ADR 0069 허용목록뿐이다 — 신원·payload 를 싣지 않는다. */
	private fun publishSpan(signal: String, message: AcceptedTelemetry): Span = tracer.spanBuilder()
		.name("telemetry publish")
		.kind(Span.Kind.PRODUCER)
		.remoteServiceName("kafka")
		.tag("messaging.system", "kafka")
		.tag("messaging.destination.name", topic)
		.tag("pulsemetry.signal", signal)
		.tag("pulsemetry.message_id", message.messageId.toString())
		.start()

	private fun send(message: AcceptedTelemetry, deadline: Instant, span: Span): PublishedRecord {
		if (!clock.instant().isBefore(deadline)) throw TelemetryPublishException("deadline_exceeded")
		val record = ProducerRecord<String, ByteArray>(
			topic,
			TelemetryRecordKey.of(message.auth.tenantId, message.auth.installationId),
			TelemetryEnvelopeCodec.encode(message),
		)
		// traceparent 만 싣는다. tracestate·baggage 는 propagator 가 넘겨도 버린다(ADR 0069).
		propagator.inject(span.context(), record) { carrier, key, value ->
			if (carrier != null && key == PlatformTracing.TRACEPARENT) {
				carrier.headers().remove(key)
				carrier.headers().add(key, value.toByteArray(Charsets.US_ASCII))
			}
		}

		val future = try {
			// metadata·버퍼를 max.block.ms 까지 기다린다. 대부분의 실패는 예외가 아니라 실패한 future 로 돌아온다.
			producer.send(record)
		} catch (e: IllegalStateException) {
			throw TelemetryPublishException("producer_closed", TelemetryLogEvent.causeOf(e))
		} catch (e: org.apache.kafka.common.errors.InterruptException) {
			Thread.currentThread().interrupt()
			throw TelemetryPublishException("interrupted", TelemetryLogEvent.causeOf(e))
		} catch (e: RuntimeException) {
			throw TelemetryPublishException(reasonOf(e), TelemetryLogEvent.causeOf(e))
		}

		val remaining = Duration.between(clock.instant(), deadline)
		if (remaining.isNegative || remaining.isZero) throw TelemetryPublishException("deadline_exceeded")
		return try {
			val metadata = future.get(remaining.toMillis().coerceAtLeast(1), TimeUnit.MILLISECONDS)
			span.tag("messaging.destination.partition.id", metadata.partition().toString())
				.tag("messaging.kafka.offset", metadata.offset().toString())
			PublishedRecord(metadata.topic(), metadata.partition(), metadata.offset())
		} catch (e: java.util.concurrent.TimeoutException) {
			throw TelemetryPublishException("deadline_exceeded", TelemetryLogEvent.causeOf(e))
		} catch (e: ExecutionException) {
			val cause = e.cause ?: e
			throw TelemetryPublishException(reasonOf(cause), TelemetryLogEvent.causeOf(cause))
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
			throw TelemetryPublishException("interrupted", TelemetryLogEvent.causeOf(e))
		}
	}

	override fun close() {
		try {
			producer.close(closeTimeout)
		} finally {
			clientMetrics?.close()
		}
	}

	private fun reasonOf(cause: Throwable): String = when (cause) {
		is RecordTooLargeException -> "record_too_large"
		is NotEnoughReplicasException, is NotEnoughReplicasAfterAppendException -> "not_enough_replicas"
		is AuthenticationException, is AuthorizationException -> "authorization_failed"
		// max.block.ms 안에 metadata·버퍼를 얻지 못했거나(BufferExhausted 포함) delivery.timeout.ms 안에 ack 가 없었다.
		is TimeoutException -> if (cause.message.orEmpty().contains("metadata", ignoreCase = true)) "metadata_timeout" else "delivery_timeout"
		else -> "broker_error"
	}
}
