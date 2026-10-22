package com.team376.pulsemetry.telemetry.consumer

import org.springframework.util.backoff.BackOff
import org.springframework.util.backoff.BackOffExecution
import java.time.Duration

/**
 * 정해진 간격을 차례로 돌려주고 끝나면 멈추는 backoff(ADR 0066 Decision 6). 기본은 1·5·30초 — 최초 시도를 더해 총 4회다.
 *
 * 대기는 오류 처리기의 기본 backoff 처리기가 컨테이너 스레드에서 잔다. 컨테이너가 멈추면 대기도 끝난다(`stoppableSleep`).
 */
class TelemetryRetryBackOff(private val intervals: List<Duration>) : BackOff {

	init {
		require(intervals.isNotEmpty()) { "재시도 간격이 하나 이상 필요하다" }
	}

	override fun start(): BackOffExecution = object : BackOffExecution {
		private var next = 0

		override fun nextBackOff(): Long =
			if (next < intervals.size) intervals[next++].toMillis() else BackOffExecution.STOP

		override fun toString(): String = "TelemetryRetryBackOff(next=$next, intervals=$intervals)"
	}
}
