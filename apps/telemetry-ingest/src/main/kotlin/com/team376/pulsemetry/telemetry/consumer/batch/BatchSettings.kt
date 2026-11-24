package com.team376.pulsemetry.telemetry.consumer.batch

import com.team376.pulsemetry.telemetry.config.TelemetryIngestProperties
import com.team376.pulsemetry.telemetry.config.TelemetryIngestProperties.Batch.PartialDefectPolicy
import com.team376.pulsemetry.telemetry.config.TelemetryIngestProperties.Batch.SingleRecordFailurePolicy
import java.time.Duration

/** 배치 경로가 쓰는 설정 값(PROJ-341). 설정 바인딩([TelemetryIngestProperties])에서 만든다. */
data class BatchSettings(
	val topic: String,
	val consumerGroup: String,
	/** 단기 재시도 간격(기본 1·5·30초). 최초를 더한 횟수가 단기 예산이다. */
	val retryIntervals: List<Duration>,
	val maxInsertBytes: Long,
	val archiveConcurrency: Int,
	val callDeadline: Duration,
	val minRemainingToStart: Duration,
	val progressInterval: Duration,
	val longWait: Duration,
	val breakerProbeInterval: Duration,
	val probeTimeout: Duration,
	val maxDiagnosticWrites: Int,
	val partialDefect: PartialDefectPolicy,
	val singleRecordFailure: SingleRecordFailurePolicy,
) {
	/** 최초 시도를 포함한 단기 예산. 이 횟수의 ClickHouse 실패에서 넘김으로 간다. */
	val shortBudget: Int get() = retryIntervals.size + 1

	/** [failures] 번째 실제 장애 뒤의 대기 — 단기 간격을 다 쓰면 [longWait]. */
	fun delayAfter(failures: Int): Duration = retryIntervals.getOrNull(failures - 1) ?: longWait

	companion object {
		fun of(properties: TelemetryIngestProperties): BatchSettings {
			val kafka = properties.telemetry.kafka
			val batch = kafka.consumer.batch
			return BatchSettings(
				topic = kafka.topic,
				consumerGroup = kafka.consumerGroup,
				retryIntervals = kafka.consumer.retryIntervals,
				maxInsertBytes = batch.maxInsertBytes,
				archiveConcurrency = batch.archiveConcurrency,
				callDeadline = batch.callDeadline,
				minRemainingToStart = batch.minRemainingToStart,
				progressInterval = batch.progressInterval,
				longWait = batch.longWait,
				breakerProbeInterval = batch.breakerProbeInterval,
				probeTimeout = batch.probeTimeout,
				maxDiagnosticWrites = batch.maxDiagnosticWrites,
				partialDefect = batch.partialDefect,
				singleRecordFailure = batch.singleRecordFailure,
			)
		}
	}
}

/**
 * 리스너 호출 하나의 마감과 진척(PROJ-341). 마감은 단조 시계다. 새 외부 작업은 [minRemainingNanos] 이상 남았을 때만 시작한다 —
 * 진행 중인 요청의 기한과 취소 여유를 더해도 `max.poll.interval.ms` 안에 들게 하려는 것이다.
 */
class BatchCall(
	private val deadlineNanos: Long,
	private val minRemainingNanos: Long,
	private val nanoTime: () -> Long = System::nanoTime,
) {
	/** 다음 호출이 건너뛸 수 있는 작업을 이번 호출에서 끝냈다 — 새 PUT·분석 조각·ledger·요약·거부·넘김 기록. 준비 단계는 여러 스레드다. */
	@Volatile
	var progressed: Boolean = false

	/** 열린 차단기의 탐침 배치다 — 첫 ClickHouse 쓰기가 성공하면 차단기를 닫고, 실패하면 이 배치를 넘긴다. */
	var probing: Boolean = false

	fun hasTimeToStart(): Boolean = deadlineNanos - nanoTime() >= minRemainingNanos

	/** 새 외부 작업을 시작하기 전에 부른다. 시간이 모자라면 [BatchDeadlineReached] 다. */
	fun ensureTime() {
		if (!hasTimeToStart()) throw BatchDeadlineReached(progressed)
	}

	companion object {
		fun start(settings: BatchSettings, nanoTime: () -> Long = System::nanoTime): BatchCall =
			BatchCall(nanoTime() + settings.callDeadline.toNanos(), settings.minRemainingToStart.toNanos(), nanoTime)
	}
}
