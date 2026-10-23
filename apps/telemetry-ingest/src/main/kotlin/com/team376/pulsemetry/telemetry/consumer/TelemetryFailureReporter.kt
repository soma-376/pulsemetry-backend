package com.team376.pulsemetry.telemetry.consumer

import com.team376.pulsemetry.telemetry.ingress.TelemetryLogEvent
import com.team376.pulsemetry.telemetry.observability.PipelineStage
import com.team376.pulsemetry.telemetry.observability.TelemetryMetric
import com.team376.pulsemetry.telemetry.observability.TelemetryObserver
import com.team376.pulsemetry.telemetry.observability.observe
import org.apache.kafka.clients.consumer.CommitFailedException
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.RetriableCommitFailedException
import org.slf4j.LoggerFactory
import org.springframework.kafka.listener.ConsumerRecordRecoverer
import org.springframework.kafka.listener.RetryListener

/**
 * 오류 처리기의 두 끝을 로그로 남긴다(ADR 0066 Decision 6·10).
 *
 * - **recoverer**([recover]) — 최초 + 재시도를 모두 실패한 record. `telemetry_retry_exhausted` 를 남기고 정상 반환해 오류 처리기가
 *   offset 을 확정하게 한다. **절대 던지지 않는다** — 던지면 오류 처리기가 상태를 지우고 같은 record 를 처음부터 다시 돈다.
 * - **retry listener**([failedDelivery]) — listener 밖에서 난 실패. 실제로는 offset commit 실패다(`AckMode.RECORD` 의 commit 은
 *   listener 가 정상 반환한 뒤 컨테이너가 한다). commit 실패는 성공이 아니다 — 오류 처리기가 같은 record 를 다시 처리한다.
 *   `telemetry_commit_failed` 를 남긴다. listener 가 던진 일시 실패는 listener 가 이미 남겼으므로 여기서는 건너뛴다.
 *
 * 둘 다 예외 메시지 원문을 남기지 않는다. 원인은 클래스 이름뿐이다.
 *
 * 두 로그는 listener 의 span 이 닫힌 뒤에 난다. 플랫폼 trace 를 켜면 record header 로 같은 trace 의 짧은 span 을 열어 그 안에서 남긴다(ADR 0069).
 *
 * 두 counter 는 생성 때 0 으로 노출한다. Prometheus `increase()` 는 처음 보인 series 의 첫 값을 증가로 세지 않으므로, 첫 실패에
 * counter 를 만들면 단발 소진·commit 실패가 경보와 대시보드에서 0 이 된다. stage 는 [RETRY_EXHAUSTED_STAGES] 전부다.
 */
class TelemetryFailureReporter(
	private val maxAttempts: Int,
	private val tracing: RecordTracing = RecordTracing(),
	private val observer: TelemetryObserver = TelemetryObserver.NONE,
) : RetryListener {

	val recoverer: ConsumerRecordRecoverer = ConsumerRecordRecoverer { record, exception -> recover(record, exception) }

	init {
		RETRY_EXHAUSTED_STAGES.forEach { observer.observe(TelemetryMetric.PIPELINE_RETRY_EXHAUSTED, 0.0, "stage" to it) }
		observer.observe(TelemetryMetric.PIPELINE_COMMIT_FAILURES, 0.0)
	}

	private fun recover(record: ConsumerRecord<*, *>, exception: Exception?) {
		try {
			val failure = transientOf(exception)
			val stage = failure?.stage ?: stageOf(exception)
			observer.observe(TelemetryMetric.PIPELINE_RETRY_EXHAUSTED, 1.0, "stage" to stage)
			tracing.around(record, "telemetry recover", "retry_exhausted", stage) {
				log.error(
					TelemetryLogEvent.RETRY_EXHAUSTED.format(
						"messageId" to failure?.messageId, "topic" to record.topic(), "partition" to record.partition(), "offset" to record.offset(),
						"stage" to stage, "reason" to reasonOf(exception), "attempt" to maxAttempts,
						"cause" to (failure?.causeType ?: TelemetryLogEvent.causeOf(rootOf(exception))),
					),
				)
			}
		} catch (_: Throwable) {
			// 로그가 실패해도 offset 확정을 막지 않는다.
		}
	}

	override fun failedDelivery(record: ConsumerRecord<*, *>, ex: Exception?, deliveryAttempt: Int) {
		if (transientOf(ex) != null) return
		try {
			val root = rootOf(ex)
			if (isCommitFailure(root)) observer.observe(TelemetryMetric.PIPELINE_COMMIT_FAILURES)
			else observer.observe(TelemetryMetric.PIPELINE_FAILURES, 1.0, "stage" to stageOf(ex), "reason" to reasonOf(ex))
			val event = if (isCommitFailure(root)) TelemetryLogEvent.COMMIT_FAILED else TelemetryLogEvent.TRANSIENT_FAILURE
			val outcome = if (isCommitFailure(root)) "commit_failed" else "transient_failed"
			tracing.around(record, "telemetry delivery failure", outcome, stageOf(ex)) {
				log.warn(
					event.format(
						"messageId" to null, "topic" to record.topic(), "partition" to record.partition(), "offset" to record.offset(),
						"stage" to stageOf(ex), "reason" to reasonOf(ex), "attempt" to deliveryAttempt, "cause" to TelemetryLogEvent.causeOf(root),
					),
				)
			}
		} catch (_: Throwable) {
			// 로그 실패로 재시도 흐름을 바꾸지 않는다.
		}
	}

	private companion object {
		val log = LoggerFactory.getLogger(TelemetryFailureReporter::class.java)

		fun rootOf(exception: Throwable?): Throwable? {
			var current = exception ?: return null
			while (true) {
				if (current is TelemetryTransientException) return current
				current = current.cause ?: return current
			}
		}

		fun transientOf(exception: Throwable?): TelemetryTransientException? = rootOf(exception) as? TelemetryTransientException

		fun isCommitFailure(root: Throwable?): Boolean = root is CommitFailedException || root is RetriableCommitFailedException

		fun stageOf(exception: Throwable?): String = when {
			transientOf(exception) != null -> transientOf(exception)!!.stage
			isCommitFailure(rootOf(exception)) -> "commit"
			else -> "container"
		}

		fun reasonOf(exception: Throwable?): String = when {
			transientOf(exception) != null -> "unavailable"
			isCommitFailure(rootOf(exception)) -> "commit_failed"
			else -> "unexpected"
		}
	}
}

/** recoverer 가 붙일 수 있는 stage — 처리 단계 이름, listener 의 분류되지 않은 일시 실패 `process`, `stageOf` 의 `commit`·`container`. */
internal val RETRY_EXHAUSTED_STAGES: List<String> = PipelineStage.entries.map { it.label } + listOf("process", "commit", "container")
