package com.team376.pulsemetry.dashboard.request

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 종료일을 포함하는 날짜 기간. 저장소 조회는 [from] 이상 [until] 미만의 반개방 구간이다 —
 * 시작일 자정부터 종료일 다음 날 자정까지, [zone] 기준. `source_time`(UTC)에 그대로 건다.
 */
data class DatePeriod(
	val startDate: LocalDate,
	val endDate: LocalDate,
	val zone: ZoneId,
) {
	init {
		require(!endDate.isBefore(startDate)) { "종료일이 시작일보다 앞이다: $startDate..$endDate" }
	}

	/** 기간의 날짜 수(양 끝 포함). */
	val days: Int get() = (ChronoUnit.DAYS.between(startDate, endDate) + 1).toInt()

	val from: Instant get() = startDate.atStartOfDay(zone).toInstant()

	val until: Instant get() = endDate.plusDays(1).atStartOfDay(zone).toInstant()

	/** 기간의 모든 날짜, 오름차순. */
	fun dates(): List<LocalDate> = (0 until days).map { startDate.plusDays(it.toLong()) }

	fun shiftDays(days: Long): DatePeriod = DatePeriod(startDate.plusDays(days), endDate.plusDays(days), zone)
}
