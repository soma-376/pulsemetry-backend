package com.team376.pulsemetry.telemetry.consumer.batch

import com.team376.pulsemetry.telemetry.ingress.TelemetryLogEvent
import com.team376.pulsemetry.telemetry.observability.TelemetryMetric
import com.team376.pulsemetry.telemetry.observability.TelemetryObserver
import com.team376.pulsemetry.telemetry.observability.observe
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom

/**
 * 소비자 하나의 ClickHouse 차단기(PROJ-341). 다른 Pod 의 상태를 기다리지 않는다.
 *
 * - **열 때** — ClickHouse 실패로 단기 예산을 다 썼거나, 진단에서 서로 다른 조각의 같은 오류를 확인했다.
 * - **열린 동안** — 다음 배치부터 ClickHouse 쓰기를 시도하지 않고 S3 넘김으로 확정한다. 없으면 배치마다 단기 예산(약 36초)을 써서
 *   소비자 6개의 처리량이 초당 17건 안팎으로 떨어진다.
 * - **닫을 때** — 열린 뒤 [probeInterval] 마다(±10% 분산) 탐침 차례가 오고, 짧은 `SELECT 1` 과 그 배치의 첫 실제 쓰기가 성공하면 닫는다.
 *
 * 소비 스레드 하나가 쓰므로 동기화하지 않는다.
 */
class ClickHouseBreaker(
	private val probeInterval: Duration,
	private val observer: TelemetryObserver = TelemetryObserver.NONE,
	private val nanoTime: () -> Long = System::nanoTime,
) {
	var isOpen: Boolean = false
		private set

	/** 연 원인 — 넘김 목록의 사유로 옮긴다. */
	var reason: HandoffReason? = null
		private set

	private var openedAt: Long = 0
	private var nextProbeAt: Long = 0

	fun open(cause: HandoffReason, consumer: String) {
		if (isOpen) return
		isOpen = true
		reason = cause
		openedAt = nanoTime()
		nextProbeAt = openedAt + jittered()
		observer.observe(TelemetryMetric.PIPELINE_BREAKERS_OPEN, 1.0)
		log.info(TelemetryLogEvent.BREAKER_OPENED.format("consumer" to consumer, "stage" to cause.stage, "cause" to cause.cause, "code" to cause.code))
	}

	fun close(consumer: String) {
		if (!isOpen) return
		val openSeconds = (nanoTime() - openedAt) / 1_000_000_000
		isOpen = false
		reason = null
		observer.observe(TelemetryMetric.PIPELINE_BREAKERS_OPEN, -1.0)
		log.info(TelemetryLogEvent.BREAKER_CLOSED.format("consumer" to consumer, "openSeconds" to openSeconds))
	}

	/** 탐침 차례인가. 열려 있을 때만 의미가 있다. */
	fun probeDue(): Boolean = isOpen && nanoTime() - nextProbeAt >= 0

	/** 탐침이 실패했다 — 다음 차례를 뒤로 민다. */
	fun deferProbe() {
		nextProbeAt = nanoTime() + jittered()
	}

	private fun jittered(): Long {
		val base = probeInterval.toNanos()
		val spread = base / 10
		return base - spread + (if (spread > 0) ThreadLocalRandom.current().nextLong(spread * 2 + 1) else 0)
	}

	private companion object {
		val log = LoggerFactory.getLogger(ClickHouseBreaker::class.java)
	}
}
