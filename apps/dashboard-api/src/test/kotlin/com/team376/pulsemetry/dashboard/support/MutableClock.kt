package com.team376.pulsemetry.dashboard.support

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** 테스트가 앞으로 돌릴 수 있는 시계. 실제 시각에서 시작한다 — ClickHouse 의 TTL 이 행을 먼저 지우지 않게 과거로 두지 않는다. */
class MutableClock(var now: Instant = Instant.now()) : Clock() {

	fun advance(duration: Duration) {
		now += duration
	}

	override fun instant(): Instant = now

	override fun getZone(): ZoneId = ZoneOffset.UTC

	override fun withZone(zone: ZoneId): Clock = this
}
