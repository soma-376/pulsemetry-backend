package com.team376.pulsemetry.telemetry.consumer

import com.team376.pulsemetry.telemetry.collector.OtlpPayloadException
import com.team376.pulsemetry.telemetry.collector.PermanentIngestException
import com.team376.pulsemetry.telemetry.credential.TelemetryCredentialRejectedException
import com.team376.pulsemetry.telemetry.ingress.InvalidEnvelopeException
import com.team376.pulsemetry.telemetry.ingress.TelemetryEnvelopeCodec
import com.team376.pulsemetry.telemetry.ingress.TelemetryLogEvent
import com.team376.pulsemetry.telemetry.observability.TelemetryMetric
import com.team376.pulsemetry.telemetry.observability.TelemetryObserver
import com.team376.pulsemetry.telemetry.observability.observe
import io.micrometer.tracing.Span
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.listener.MessageListener
import org.springframework.kafka.support.KafkaHeaders
import java.nio.ByteBuffer
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * 일시 실패. **원인 메시지를 담지 않는다** — Spring Kafka 가 이 예외를 로그에 남기는데, 원인(드라이버·SDK·ClickHouse)의
 * 메시지에는 payload 조각이 섞일 수 있다. 원인은 클래스 이름([causeType])만 남긴다. 스택을 만들지 않는다.
 */
class TelemetryTransientException(
	val messageId: UUID?,
	val stage: String,
	val causeType: String,
) : RuntimeException("telemetry transient failure: stage=$stage cause=$causeType", null, false, false)

/**
 * ingress record 하나를 처리한다(ADR 0066 Decision 6). 단건 listener 이고 `AckMode.RECORD` 다 — 정상 반환이 곧 offset 확정이다.
 *
 * | 결과 | 처리 |
 * |---|---|
 * | 정상·빈 OTLP | 정상 반환 → 확정 |
 * | 잘못된 envelope · RDB 거부 · 디코딩·마스킹 실패 · 파이프라인 영구 실패 | `telemetry_permanent_failure` 로그 후 정상 반환 → 확정 |
 * | 그 밖(RDB·S3·ClickHouse 일시 장애, 분류되지 않은 예외) | `telemetry_transient_failure` 로그 후 [TelemetryTransientException] 을 던진다 → 오류 처리기의 재시도 |
 *
 * 영구 실패를 던지지 않고 여기서 끝내는 것은 오류 처리기에 영구·일시 분류를 둘로 나눠 두지 않기 위해서다. 파이프라인의 영구
 * 실패는 그 전에 운영 기록(ADR 0021)을 남겼다. 예외를 삼켜 일시 실패를 성공으로 만들지 않는다 — `finally` 에서 확정하지 않는다.
 *
 * 로그는 [TelemetryLogEvent] 한 줄이다. 메시지 ID·topic·partition·offset·단계·사유·시도 횟수만 남기고 payload·토큰·예외 메시지 원문은 남기지 않는다.
 * message ID 를 읽을 수 없으면(잘못된 envelope) topic·partition·offset 으로 식별한다.
 *
 * 플랫폼 trace 를 켜면 시도마다 consumer span 하나를 열고 그 안에서 처리·로그를 한다(ADR 0069). 결과는 span 의 `pulsemetry.outcome` 이다.
 */
class TelemetryRecordListener(
	private val process: ProcessTelemetry,
	private val observer: TelemetryObserver = TelemetryObserver.NONE,
	private val clock: Clock = Clock.systemUTC(),
	private val tracing: RecordTracing = RecordTracing(),
) : MessageListener<ByteArray, ByteArray> {

	/** 값이 null 인 record(tombstone)도 올 수 있다 — codec 이 잘못된 envelope 로 확정한다. */
	override fun onMessage(record: ConsumerRecord<ByteArray, ByteArray>) {
		var signal = "unknown"
		var outcome = "transient_failed"
		val attempt = deliveryAttempt(record)
		val span = tracing.start(record, "telemetry process", attempt)
		observer.observe(TelemetryMetric.PIPELINE_INFLIGHT)
		try {
			tracing.scope(span).use {
				try {
					handle(record, attempt, span) { signal = it }.also { outcome = it }
				} finally {
					traceCompleted(span, signal, outcome, attempt)
				}
			}
		} finally {
			span.tag("pulsemetry.signal", signal).tag("pulsemetry.outcome", outcome).end()
			observer.observe(TelemetryMetric.PIPELINE_ATTEMPTS, 1.0, "signal" to signal, "outcome" to outcome)
			observer.observe(TelemetryMetric.PIPELINE_INFLIGHT, -1.0)
		}
	}

	/** 처리 결과(`completed`·`permanent_rejected`)를 돌려준다. 일시 실패는 [TelemetryTransientException] 을 던진다. */
	private fun handle(record: ConsumerRecord<ByteArray, ByteArray>, attempt: Int, span: Span, decoded: (String) -> Unit): String {
		val message = try {
			TelemetryEnvelopeCodec.decode(record.value())
		} catch (e: InvalidEnvelopeException) {
			permanent(record, null, "envelope", e.defect, attempt, span)
			return "permanent_rejected"
		}
		val signal = message.payload.signal.name.lowercase()
		decoded(signal)
		span.tag("pulsemetry.message_id", message.messageId.toString())
		fun age() = Duration.between(message.acceptedAt, clock.instant()).let { it.seconds + it.nano / 1e9 }
		observer.observe(TelemetryMetric.PIPELINE_ACCEPTED_TO_ATTEMPT_START, age(), "signal" to signal)
		try {
			process.execute(message)
			observer.observe(TelemetryMetric.PIPELINE_ACCEPTED_TO_COMPLETION, age(), "signal" to signal)
			return "completed"
		} catch (e: TelemetryCredentialRejectedException) {
			permanent(record, message.messageId, "authorize", e.code, attempt, span)
		} catch (e: OtlpPayloadException) {
			permanent(record, message.messageId, "decode", e.reason, attempt, span)
		} catch (e: PermanentIngestException) {
			permanent(record, message.messageId, "pipeline", "pipeline_rejected", attempt, span)
		} catch (e: TelemetryStageFailure) {
			throw transient(record, message.messageId, e.stage, e.cause, attempt, span)
		} catch (e: Exception) {
			throw transient(record, message.messageId, "process", e, attempt, span)
		}
		return "permanent_rejected"
	}

	/** sampled 시도는 처리 span 안에서 완료 한 줄을 남긴다(ADR 0069). 로그 실패가 offset 판정을 바꾸지 않는다. */
	private fun traceCompleted(span: Span, signal: String, outcome: String, attempt: Int) {
		runCatching {
			if (tracing.sampled(span)) {
				log.info(TelemetryLogEvent.PLATFORM_TRACE_COMPLETED.format("stage" to "ingest", "signal" to signal, "outcome" to outcome, "attempt" to attempt))
			}
		}
	}

	private fun permanent(record: ConsumerRecord<*, *>, messageId: UUID?, stage: String, reason: String, attempt: Int, span: Span) {
		span.tag("pulsemetry.stage", stage).tag("pulsemetry.reason", reason)
		tracing.fail(span, reason)
		observer.observe(TelemetryMetric.PIPELINE_FAILURES, 1.0, "stage" to stage, "reason" to reason)
		log.warn(
			TelemetryLogEvent.PERMANENT_FAILURE.format(
				"messageId" to messageId, "topic" to record.topic(), "partition" to record.partition(), "offset" to record.offset(),
				"stage" to stage, "reason" to reason, "attempt" to attempt,
			),
		)
	}

	private fun transient(record: ConsumerRecord<*, *>, messageId: UUID?, stage: String, cause: Throwable?, attempt: Int, span: Span): TelemetryTransientException {
		observer.observe(TelemetryMetric.PIPELINE_FAILURES, 1.0, "stage" to stage, "reason" to "unavailable")
		val causeType = TelemetryLogEvent.causeOf(cause)
		// 원본 예외를 span 에 넘기지 않는다 — 메시지에 payload·SQL 이 섞일 수 있다. 클래스 이름만 남긴다.
		span.tag("pulsemetry.stage", stage)
		tracing.fail(span, causeType)
		log.warn(
			TelemetryLogEvent.TRANSIENT_FAILURE.format(
				"messageId" to messageId, "topic" to record.topic(), "partition" to record.partition(), "offset" to record.offset(),
				"stage" to stage, "reason" to "unavailable", "attempt" to attempt, "cause" to causeType,
			),
		)
		return TelemetryTransientException(messageId, stage, causeType)
	}

	companion object {
		private val log = LoggerFactory.getLogger(TelemetryRecordListener::class.java)

		/** 컨테이너가 붙이는 시도 횟수 header(`deliveryAttemptHeader=true`). 없으면 첫 시도다. */
		fun deliveryAttempt(record: ConsumerRecord<*, *>): Int =
			record.headers().lastHeader(KafkaHeaders.DELIVERY_ATTEMPT)?.value()
				?.takeIf { it.size == Int.SIZE_BYTES }?.let { ByteBuffer.wrap(it).int } ?: 1
	}
}
