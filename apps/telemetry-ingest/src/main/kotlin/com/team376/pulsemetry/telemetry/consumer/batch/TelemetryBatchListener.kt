package com.team376.pulsemetry.telemetry.consumer.batch

import com.team376.pulsemetry.telemetry.ingress.TelemetryLogEvent
import com.team376.pulsemetry.telemetry.observability.TelemetryMetric
import com.team376.pulsemetry.telemetry.observability.TelemetryObserver
import com.team376.pulsemetry.telemetry.observability.observe
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.slf4j.LoggerFactory
import org.springframework.kafka.listener.BatchMessageListener
import org.springframework.kafka.support.Acknowledgment
import java.time.Clock
import java.time.Duration

/**
 * ingress 배치 하나를 처리한다(PROJ-341 · ADR 0083). 원본 `ConsumerRecords` 를 받고(`wantsPollResult`), `AckMode.BATCH` 라 정상 반환이 곧
 * 파티션마다 배치 끝의 다음 offset 확정이다. **모든 레코드가 적재·거부·넘김 기록 중 하나로 끝나야** 정상 반환한다.
 *
 * 미완료면 [BatchIncompleteException] 중 하나를 던진다 — 실패 인덱스가 없어 오류 처리기의 배치 fallback 이 할당 파티션을 멈추고 같은
 * 메모리 배치로 다시 부른다. 다음 호출까지의 대기는 여기서 정해 [BatchDeliveryState] 에 둔다:
 *
 * | 사유 | 실제 장애 횟수 | 대기 |
 * |---|---|---|
 * | ClickHouse 장애(단기 예산 안) | +1 | 1·5·30초 — 예산을 다 쓰면 처리기가 넘김으로 끝낸다 |
 * | 인증 DB·S3·보강·경계·요약·넘김 목록 쓰기 | +1 | 1·5·30초, 그 뒤 긴 대기(5분) + `telemetry_batch_waiting` 한 번 |
 * | 예상 밖 예외 | +1 | 같은 일정 + 처음 한 번 `telemetry_batch_unexpected_failure` |
 * | 마감, 진척 있음 | 그대로 | 짧은 대기 — 다음 호출이 이어간다 |
 * | 마감, 진척 없음 | 그대로(진척 없는 호출 +1) | 장애 일정 — 반복되면 `telemetry_batch_stalled` |
 * | 팀 정책 보류 | 그대로 | 긴 대기 + `telemetry_batch_waiting` 한 번 |
 *
 * 어느 일정도 멈추지(`STOP`) 않는다 — 멈추면 Spring 이 recoverer 를 불러 레코드를 건너뛸 수 있다. JVM `Error` 는 감싸지 않는다(컨테이너가
 * 비정상 중단한다, 단건 경로와 같다).
 *
 * 작업 캐시([BatchWork])는 소비 스레드마다 하나이고 원본 `ConsumerRecords` 객체가 같을 때만 이어 쓴다. 재할당·정상 반환 때 버린다.
 */
class TelemetryBatchListener(
	private val processor: BatchIngestProcessor,
	private val state: BatchDeliveryState,
	private val settings: BatchSettings,
	private val observer: TelemetryObserver = TelemetryObserver.NONE,
	private val clock: Clock = Clock.systemUTC(),
	private val nanoTime: () -> Long = System::nanoTime,
) : BatchMessageListener<ByteArray, ByteArray> {

	private val current = ThreadLocal<BatchWork?>()

	override fun wantsPollResult(): Boolean = true

	override fun onMessage(data: List<ConsumerRecord<ByteArray, ByteArray>>) {
		throw UnsupportedOperationException("telemetry batch listener 는 원본 ConsumerRecords 만 받는다")
	}

	override fun onMessage(records: ConsumerRecords<ByteArray, ByteArray>, acknowledgment: Acknowledgment?, consumer: Consumer<ByteArray, ByteArray>) {
		// 이전 전달의 완료 표시가 새 실패를 완료로 판정하지 않게 호출마다 비운다.
		state.clearCompleted()
		val work = current.get()?.takeIf { it.records === records } ?: BatchWork(records).also { current.set(it) }
		work.invocations++
		val call = BatchCall.start(settings, nanoTime)
		try {
			processor.process(work, call)
		} catch (error: Error) {
			current.remove()
			throw error
		} catch (exception: Exception) {
			throw failed(work, exception)
		}
		completed(work)
		current.remove()
		// 마지막 지점에서만 표시한다 — 이 뒤로는 던질 일이 없다.
		state.markCompleted(records)
	}

	/** 재할당·상실 — 소유권을 잃은 배치의 작업 캐시를 버린다. 소비 스레드에서 불린다. */
	fun discard() {
		current.remove()
	}

	private fun failed(work: BatchWork, exception: Exception): BatchIncompleteException {
		val failure = classify(exception)
		val delay: Duration = when (failure) {
			is BatchDeadlineReached -> if (failure.progressed) {
				// 진척이 있으면 연속 정체가 끊긴다. 실제 장애 횟수는 그대로 둔다.
				work.stalls = 0
				settings.progressInterval
			} else {
				work.stalls++
				if (work.stalls >= 2) {
					log.warn(TelemetryLogEvent.BATCH_STALLED.format("topic" to settings.topic, *ranges(work), "stalls" to work.stalls, "attempt" to work.invocations))
				}
				settings.delayAfter(work.stalls)
			}
			is HeldBatchFailure -> {
				waiting(work, failure)
				settings.longWait
			}
			is ClickHouseBatchFailure -> {
				work.failures++
				settings.delayAfter(work.failures)
			}
			is DependencyBatchFailure, is UnexpectedBatchFailure -> {
				work.failures++
				if (failure is UnexpectedBatchFailure && !work.unexpectedReported) {
					work.unexpectedReported = true
					log.error(
						TelemetryLogEvent.BATCH_UNEXPECTED_FAILURE.format(
							"topic" to settings.topic, *ranges(work), "stage" to failure.stage, "cause" to failure.causeType, "attempt" to work.invocations,
						),
					)
				}
				settings.delayAfter(work.failures).also { if (work.failures > settings.retryIntervals.size) waiting(work, failure) }
			}
		}
		if (failure !is BatchDeadlineReached) work.lastFailureWasClickHouse = failure is ClickHouseBatchFailure
		state.requestDelay(delay.toMillis())
		observer.observe(TelemetryMetric.PIPELINE_BATCH_INVOCATIONS, 1.0, "outcome" to "transient_failed", "reason" to reasonLabel(failure))
		log.warn(
			TelemetryLogEvent.TRANSIENT_FAILURE.format(
				"topic" to settings.topic, *ranges(work), "stage" to failure.stage, "reason" to failure.kind, "attempt" to work.invocations,
				"failures" to work.failures, "cause" to failure.causeType, "waitMillis" to delay.toMillis(),
			),
		)
		return failure
	}

	private fun classify(exception: Exception): BatchIncompleteException = when (exception) {
		is BatchIncompleteException -> exception
		is DependencyCallException -> DependencyBatchFailure(exception.stage, TelemetryLogEvent.causeOf(exception.cause))
		is ClickHouseCallException -> ClickHouseBatchFailure(exception.stage, TelemetryLogEvent.causeOf(exception.cause))
		else -> UnexpectedBatchFailure("process", TelemetryLogEvent.causeOf(exception))
	}

	/** 넘길 근거가 없는 긴 대기에 들어간다 — 배치마다 한 번 남기고, 복구 때 한 번 더 남긴다. */
	private fun waiting(work: BatchWork, failure: BatchIncompleteException) {
		if (work.waitingReported) return
		work.waitingReported = true
		log.warn(
			TelemetryLogEvent.BATCH_WAITING.format(
				"topic" to settings.topic, *ranges(work), "stage" to failure.stage, "reason" to failure.kind, "cause" to failure.causeType,
				"oldestAgeSeconds" to oldestAgeSeconds(work), "failures" to work.failures,
			),
		)
	}

	private fun completed(work: BatchWork) {
		runCatching {
			for (item in work.items) {
				val outcome = when (item.status) {
					BatchRecord.Status.LOADED, BatchRecord.Status.EMPTY -> "completed"
					BatchRecord.Status.REJECTED -> "permanent_rejected"
					BatchRecord.Status.HANDED_OFF -> "handed_off"
					BatchRecord.Status.PENDING -> "transient_failed"
				}
				observer.observe(TelemetryMetric.PIPELINE_ATTEMPTS, 1.0, "signal" to item.signalLabel, "outcome" to outcome)
				val accepted = item.message?.acceptedAt
				if (accepted != null && item.status == BatchRecord.Status.LOADED) {
					observer.observe(TelemetryMetric.PIPELINE_ACCEPTED_TO_COMPLETION, seconds(Duration.between(accepted, clock.instant())), "signal" to item.signalLabel)
				}
			}
			observer.observe(TelemetryMetric.PIPELINE_BATCH_INVOCATIONS, 1.0, "outcome" to "completed", "reason" to "none")
			// 넘길 수 없어 긴 대기에 들어갔던 배치만 복구 시점을 남긴다 — 단기 재시도·넘김은 각자의 줄이 있다.
			if (work.waitingReported) {
				log.info(
					TelemetryLogEvent.BATCH_RECOVERED.format(
						"topic" to settings.topic, *ranges(work), "failures" to work.failures, "attempt" to work.invocations,
						"handedOff" to work.items.count { it.status == BatchRecord.Status.HANDED_OFF },
					),
				)
			}
		}
	}

	private fun ranges(work: BatchWork): Array<Pair<String, Any?>> = BatchLogFields.ranges(PartitionRange.of(work.items))

	private fun oldestAgeSeconds(work: BatchWork): Long? =
		work.items.filter { !it.terminal }.mapNotNull { it.message?.acceptedAt }.minOrNull()?.let { Duration.between(it, clock.instant()).seconds }

	private fun reasonLabel(failure: BatchIncompleteException): String = when (failure) {
		is ClickHouseBatchFailure -> "clickhouse"
		is DependencyBatchFailure -> "dependency"
		is UnexpectedBatchFailure -> "unexpected"
		is BatchDeadlineReached -> failure.kind
		is HeldBatchFailure -> "other"
	}

	private fun seconds(duration: Duration): Double = duration.seconds + duration.nano / 1e9

	private companion object {
		val log = LoggerFactory.getLogger(TelemetryBatchListener::class.java)
	}
}
