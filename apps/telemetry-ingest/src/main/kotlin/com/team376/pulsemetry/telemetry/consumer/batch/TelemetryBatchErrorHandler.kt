package com.team376.pulsemetry.telemetry.consumer.batch

import com.team376.pulsemetry.telemetry.ingress.TelemetryLogEvent
import com.team376.pulsemetry.telemetry.observability.TelemetryMetric
import com.team376.pulsemetry.telemetry.observability.TelemetryObserver
import com.team376.pulsemetry.telemetry.observability.observe
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.common.TopicPartition
import org.slf4j.LoggerFactory
import org.springframework.kafka.KafkaException
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener
import org.springframework.kafka.listener.ConsumerRecordRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.listener.ErrorHandlingUtils
import org.springframework.kafka.listener.MessageListenerContainer
import org.springframework.util.backoff.BackOff
import org.springframework.util.backoff.BackOffExecution
import java.time.Duration

/**
 * 배치 리스너의 오류 처리기(PROJ-341 · ADR 0083). 비트랜잭션 배치, `AckMode.BATCH`, `syncCommits=true`, `seekAfterError=true`,
 * `wantsPollResult()=true`, `BatchInterceptor` 미사용을 전제로 한다.
 *
 * ## 진입 시점의 판별
 *
 * | 진입 | 처리 |
 * |---|---|
 * | 이번 전달이 완료 표시돼 있다 — 리스너가 정상 반환한 뒤 동기 커밋이 실패했다 | 재호출·seek·커밋 없이 `telemetry_commit_failed` 를 실은 WARN 예외를 던진다. Spring 은 ack 등록을 건너뛰고 다음 poll 로 간다(위치는 이미 전진). 쓰기는 끝났으므로 누락은 없다 — 뒤 배치의 커밋이 따라잡기 전에 재할당·재시작되면 재전달(중복)만 남는다 |
 * | 그 밖 — 미완료 배치 | 배치 fallback(`super.handleBatch`)에 맡긴다. 할당 파티션을 멈추고 대기 중 poll 하며 같은 메모리 배치로 리스너를 다시 부른다 |
 *
 * 완료 여부는 예외 클래스나 할당 목록이 아니라 리스너의 완료 표시로 가른다 — 세대 오류의 `CommitFailedException` 은 핸들러 진입 때
 * 옛 할당이 남아 있어 할당으로는 판별할 수 없다.
 *
 * ## 재할당 중 ack 후퇴 방지
 *
 * 미완료 배치의 재시도 대기 중 파티션이 다른 소비자로 넘어가도 Spring 은 재호출 전에 원본 배치와 새 할당을 대조하지 않는다. 새 소유자가
 * 확정한 위치(예: 251)를 옛 배치의 ack(201)가 덮어 후퇴시킬 수 있다. 진입 때와 `super.handleBatch` 정상 반환 직후의 회수·상실 카운터
 * ([BatchDeliveryState], 같은 `Consumer` 객체 키)를 비교해 바뀌었으면 INFO 제어 예외로 이번 옛 배치의 ack 등록을 건너뛴다 — 커밋 시도 0건.
 * EAGER 재할당은 할당 전체를 회수하므로 다음 소유자가 확정 위치부터 다시 읽어 누락이 없다.
 *
 * ## 일정
 *
 * [BatchRetryBackOff] 가 리스너가 정한 대기를 돌려주고 `STOP` 을 내지 않는다. 모든 예외를 재시도로 분류한다(`setClassifications(emptyMap(), true)`)
 * — 리스너 밖에서 fallback 에 닿은 예외도 recoverer 로 바로 가지 않게 한다. 그런 예외는 경보와 함께 긴 대기다.
 */
class TelemetryBatchErrorHandler(
	private val state: BatchDeliveryState,
	private val settings: BatchSettings,
	private val observer: TelemetryObserver = TelemetryObserver.NONE,
) : DefaultErrorHandler(LAST_DEFENCE, BatchRetryBackOff(state, settings.longWait)) {

	init {
		setClassifications(emptyMap(), true)
		observer.observe(TelemetryMetric.PIPELINE_COMMIT_FAILURES, 0.0)
		observer.observe(TelemetryMetric.PIPELINE_REBALANCE_ACK_SKIPPED, 0.0)
		observer.observe(TelemetryMetric.PIPELINE_HANDOFF_RECORDS, 0.0, "reason" to "budget_exhausted")
		observer.observe(TelemetryMetric.PIPELINE_HANDOFF_RECORDS, 0.0, "reason" to "breaker_open")
		observer.observe(TelemetryMetric.PIPELINE_HANDOFF_RECORDS, 0.0, "reason" to "breaker_probe_failed")
		observer.observe(TelemetryMetric.PIPELINE_HANDOFF_RECORDS, 0.0, "reason" to "single_record")
	}

	override fun handleBatch(
		thrownException: Exception,
		data: ConsumerRecords<*, *>,
		consumer: Consumer<*, *>,
		container: MessageListenerContainer,
		invokeListener: Runnable,
	) {
		if (state.takeCompleted(data)) throw commitFailed(thrownException, data)

		val unwrapped = ErrorHandlingUtils.unwrapIfNeeded(thrownException)
		if (unwrapped !is BatchIncompleteException) {
			// 리스너 밖에서 온 예외 — 리스너가 대기를 정하지 않았다. 긴 대기로 두고 경보를 남긴다.
			state.requestDelay(settings.longWait.toMillis())
			log.error(
				TelemetryLogEvent.BATCH_UNEXPECTED_FAILURE.format(
					"topic" to settings.topic, *ranges(data), "stage" to "container", "cause" to TelemetryLogEvent.causeOf(unwrapped),
				),
			)
		}
		val before = state.rebalanceCount(consumer)
		super.handleBatch(thrownException, data, consumer, container, invokeListener)
		if (state.rebalanceCount(consumer) != before) {
			observer.observe(TelemetryMetric.PIPELINE_REBALANCE_ACK_SKIPPED)
			throw KafkaException(
				TelemetryLogEvent.REBALANCE_ACK_SKIPPED.format("topic" to settings.topic, *ranges(data)),
				KafkaException.Level.INFO,
				null,
			)
		}
	}

	/** 저장 완료 뒤 동기 커밋 실패 — 앱 지표 한 번, 컨테이너의 `selfLog` 가 WARN 한 번. 예외 설명 한 줄에 경보 코드와 추적 필드를 싣는다. */
	private fun commitFailed(thrownException: Exception, data: ConsumerRecords<*, *>): KafkaException {
		val root = ErrorHandlingUtils.unwrapIfNeeded(thrownException) ?: thrownException
		observer.observe(TelemetryMetric.PIPELINE_COMMIT_FAILURES)
		return KafkaException(
			TelemetryLogEvent.COMMIT_FAILED.format("cause" to TelemetryLogEvent.causeOf(root), "topic" to settings.topic, *ranges(data)),
			KafkaException.Level.WARN,
			thrownException,
		)
	}

	private fun ranges(data: ConsumerRecords<*, *>): Array<Pair<String, Any?>> = BatchLogFields.ranges(
		data.partitions().map { partition ->
			val records = data.records(partition)
			PartitionRange(partition.partition(), records.first().offset(), records.last().offset())
		},
	)

	private companion object {
		val log = LoggerFactory.getLogger(TelemetryBatchErrorHandler::class.java)

		/**
		 * 마지막 방어선 — 어떤 일정도 `STOP` 을 내지 않으므로 불리지 않아야 한다. 불리면 레코드를 건너뛰지 않도록 던진다. Spring 은 배치 시작으로
		 * 되감고 다음 poll 에서 다시 받는다.
		 */
		val LAST_DEFENCE = ConsumerRecordRecoverer { record, _ ->
			log.error("event=ingest_batch_recoverer_reached topic={} partition={} offset={}", record.topic(), record.partition(), record.offset())
			throw IllegalStateException("배치 recoverer 는 레코드를 건너뛰지 않는다")
		}
	}
}

/** 리스너가 정한 다음 대기를 돌려준다. 정하지 않았으면 긴 대기다. **`STOP` 을 내지 않는다**(PROJ-341). */
class BatchRetryBackOff(private val state: BatchDeliveryState, private val fallback: Duration) : BackOff {
	override fun start(): BackOffExecution = BackOffExecution { state.takeDelay(fallback.toMillis()) }
}

/**
 * 회수·상실을 소비자별 카운터에 올리고 소유권을 잃은 배치의 작업 캐시를 버린다(PROJ-341). 소비자들이 이 인스턴스를 공유하므로 콜백에 온
 * `Consumer` 객체를 키로 쓴다. 상실 뒤 회수가 이어져 두 번 올라가도 핸들러는 값이 달라졌는지만 본다.
 */
class BatchRebalanceListener(
	private val state: BatchDeliveryState,
	private val listener: TelemetryBatchListener,
) : ConsumerAwareRebalanceListener {

	override fun onPartitionsRevokedBeforeCommit(consumer: Consumer<*, *>, partitions: Collection<TopicPartition>) {
		state.rebalanced(consumer)
		listener.discard()
	}

	override fun onPartitionsLost(consumer: Consumer<*, *>, partitions: Collection<TopicPartition>) {
		state.rebalanced(consumer)
		listener.discard()
	}
}
