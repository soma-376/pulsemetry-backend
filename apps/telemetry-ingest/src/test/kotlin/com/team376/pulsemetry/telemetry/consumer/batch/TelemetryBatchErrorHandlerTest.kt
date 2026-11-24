package com.team376.pulsemetry.telemetry.consumer.batch

import com.team376.pulsemetry.telemetry.config.TelemetryIngestProperties.Batch.PartialDefectPolicy
import com.team376.pulsemetry.telemetry.config.TelemetryIngestProperties.Batch.SingleRecordFailurePolicy
import com.team376.pulsemetry.telemetry.observability.TelemetryMetric
import com.team376.pulsemetry.telemetry.observability.TelemetryObserver
import org.apache.kafka.clients.consumer.CommitFailedException
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.TimeoutException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.kafka.KafkaException
import org.springframework.kafka.listener.MessageListenerContainer
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * 배치 오류 처리기(PROJ-341)의 진입 판별을 실제 Spring 배치 fallback 위에서 고정한다 — 저장 완료 뒤 커밋 실패는 재호출 0회, 재시도 중 재할당이
 * 일어나면 옛 배치의 ack 를 건너뛴다. 소비자·컨테이너는 대역이다.
 */
class TelemetryBatchErrorHandlerTest {

	private val state = BatchDeliveryState()
	private val consumer: Consumer<*, *> = mock(Consumer::class.java)
	private val container: MessageListenerContainer = mock(MessageListenerContainer::class.java).also { given(it.isRunning).willReturn(true) }
	private val metrics = mutableListOf<TelemetryMetric>()
	private val observer = TelemetryObserver { metric, value, _ -> if (value > 0) metrics += metric }
	private val handler = TelemetryBatchErrorHandler(state, settings(), observer)
	private val records = records()

	@Test
	@DisplayName("저장 완료 뒤 동기 커밋 실패는 재호출·seek 없이 commit_failed WARN 예외 한 번이다")
	fun commitFailureAfterCompletion() {
		state.markCompleted(records)
		val invocations = AtomicInteger()

		val thrown = catchThrowable { handler.handleBatch(TimeoutException("commit"), records, consumer, container) { invocations.incrementAndGet() } }

		assertThat(thrown).isInstanceOf(KafkaException::class.java)
		val kafka = thrown as KafkaException
		assertThat(kafka.message).isEqualTo("event=telemetry_commit_failed cause=TimeoutException topic=t p0=51-90 p1=101-200")
		assertThat(kafka.cause).isInstanceOf(TimeoutException::class.java)
		assertThat(invocations.get()).isZero()
		verify(consumer, never()).seek(TopicPartition("t", 0), 51)
		assertThat(metrics).containsExactly(TelemetryMetric.PIPELINE_COMMIT_FAILURES)
		assertThat(state.takeCompleted(records)).describedAs("표시는 한 번만 쓴다").isFalse()
	}

	@Test
	@DisplayName("완료 표시가 다른 전달의 것이면 미완료로 보고 fallback 으로 다시 부른다")
	fun previousCompletionDoesNotCount() {
		state.markCompleted(records())
		state.requestDelay(1)
		val invocations = AtomicInteger()

		handler.handleBatch(CommitFailedException("generation"), records, consumer, container) { invocations.incrementAndGet() }

		assertThat(invocations.get()).isEqualTo(1)
		assertThat(metrics).doesNotContain(TelemetryMetric.PIPELINE_COMMIT_FAILURES)
	}

	@Test
	@DisplayName("미완료 배치의 재시도가 성공하면 정상 반환한다(ack 등록)")
	fun retrySucceeds() {
		state.requestDelay(1)
		val invocations = AtomicInteger()

		handler.handleBatch(ClickHouseBatchFailure("insert", "x"), records, consumer, container) { invocations.incrementAndGet() }

		assertThat(invocations.get()).isEqualTo(1)
	}

	@Test
	@DisplayName("재시도 대기 중 회수·상실이 일어나면 재시도가 성공해도 옛 배치의 ack 를 건너뛴다 — INFO 한 번, 커밋 실패 지표 0")
	fun rebalanceDuringRetrySkipsAck() {
		state.requestDelay(1)

		val thrown = catchThrowable {
			handler.handleBatch(ClickHouseBatchFailure("insert", "x"), records, consumer, container) {
				// 재호출 직전 poll 에서 재할당이 일어났다 — 상실 뒤 회수가 이어져 두 번 올라가도 한 번만 건너뛴다.
				state.rebalanced(consumer)
				state.rebalanced(consumer)
			}
		}

		assertThat(thrown).isInstanceOf(KafkaException::class.java)
		assertThat((thrown as KafkaException).message).isEqualTo("event=kafka_rebalance_ack_skipped topic=t p0=51-90 p1=101-200")
		assertThat(metrics).containsExactly(TelemetryMetric.PIPELINE_REBALANCE_ACK_SKIPPED)
	}

	@Test
	@DisplayName("다른 소비자의 재할당은 이 소비자의 ack 를 막지 않는다")
	fun otherConsumerRebalanceDoesNotSkip() {
		val other: Consumer<*, *> = mock(Consumer::class.java)
		state.requestDelay(1)

		handler.handleBatch(ClickHouseBatchFailure("insert", "x"), records, consumer, container) { state.rebalanced(other) }

		assertThat(metrics).isEmpty()
	}

	@Test
	@DisplayName("리스너 밖에서 온 예외는 긴 대기를 정하고 클래스가 바뀌어도 recoverer 로 가지 않는다")
	fun foreignExceptionWaitsLong() {
		var attempts = 0
		val started = System.nanoTime()

		handler.handleBatch(IllegalStateException("outside"), records, consumer, container) {
			attempts++
			if (attempts < 3) {
				state.requestDelay(1)
				throw if (attempts == 1) UnexpectedBatchFailure("process", "NullPointerException") else DependencyBatchFailure("archive", "S3Exception")
			}
		}

		assertThat(attempts).isEqualTo(3)
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(LONG_WAIT)
	}

	@Test
	@DisplayName("대기 일정은 STOP 을 내지 않는다")
	fun backOffNeverStops() {
		val execution = BatchRetryBackOff(state, Duration.ofMinutes(5)).start()

		repeat(100) { assertThat(execution.nextBackOff()).isEqualTo(Duration.ofMinutes(5).toMillis()) }
		state.requestDelay(1_000)
		assertThat(execution.nextBackOff()).isEqualTo(1_000)
	}

	@Test
	@DisplayName("소비자 종료 통지는 재할당 카운터를 지우고 가동 중인 소비자의 값은 남긴다")
	fun forgetsClosedConsumer() {
		val running: Consumer<*, *> = mock(Consumer::class.java)
		state.rebalanced(running)
		repeat(5) {
			val transient: Consumer<*, *> = mock(Consumer::class.java)
			state.rebalanced(transient)
			state.forget(transient)
			state.forget(transient)
		}

		assertThat(state.trackedConsumers()).isEqualTo(1)
		assertThat(state.rebalanceCount(running)).isEqualTo(1)
	}

	private fun records(): ConsumerRecords<ByteArray, ByteArray> {
		fun range(partition: Int, from: Long, to: Long) = TopicPartition("t", partition) to (from..to).map { ConsumerRecord("t", partition, it, ByteArray(0), ByteArray(0)) }
		return ConsumerRecords(mapOf(range(0, 51, 90), range(1, 101, 200)), emptyMap())
	}

	private fun settings() = BatchSettings(
		topic = "t",
		consumerGroup = "g",
		retryIntervals = listOf(Duration.ofMillis(1), Duration.ofMillis(1), Duration.ofMillis(1)),
		maxInsertBytes = 10_000_000,
		archiveConcurrency = 1,
		callDeadline = Duration.ofSeconds(60),
		minRemainingToStart = Duration.ofSeconds(1),
		progressInterval = Duration.ofMillis(1),
		longWait = LONG_WAIT,
		breakerProbeInterval = Duration.ofMinutes(5),
		probeTimeout = Duration.ofSeconds(1),
		maxDiagnosticWrites = 32,
		partialDefect = PartialDefectPolicy.REJECT,
		singleRecordFailure = SingleRecordFailurePolicy.HANDOFF,
	)

	private companion object {
		val LONG_WAIT: Duration = Duration.ofMillis(300)
	}
}
