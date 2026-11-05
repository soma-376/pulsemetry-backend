package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.request.DatePeriod
import java.time.Instant

/** 전체 선택 기간은 6~12개로 계획하고 응답은 asOf 이전만 자른다(허브 ADR 0033). */
class TrendBucketPlan(val period: DatePeriod, val asOf: Instant) {
	val intervalSeconds: Long? = when (period.days) {
		1 -> 10800L
		2 -> 21600L
		in 3..6 -> 43200L
		in 7..12 -> 86400L
		in 13..24 -> 172800L
		in 25..36 -> 259200L
		in 37..84 -> 604800L
		in 85..168 -> 1209600L
		else -> null
	}
	val intervalMonths: Int? = if (intervalSeconds == null) 1 else null
	val effectiveEnd: Instant = maxOf(period.from, minOf(period.until, asOf))

	/** 월말 보정은 항상 원래 시작일에서 계산한다. 13번째 잔여 구간은 마지막 구간에 합친다. */
	val plannedStarts: List<Instant> = buildList {
		var index = 0L
		while (size < 12) {
			val start = intervalSeconds?.let { period.from.plusSeconds(index * it) }
				?: period.startDate.plusMonths(index).atStartOfDay(period.zone).toInstant()
			if (start >= period.until) break
			add(start)
			index++
		}
	}
	val buckets: List<Bucket> = plannedStarts.mapIndexedNotNull { index, start ->
		if (start >= effectiveEnd) null else {
			val plannedEnd = plannedStarts.getOrNull(index + 1) ?: period.until
			Bucket(index, start, minOf(plannedEnd, effectiveEnd), if (asOf < plannedEnd) "in_progress" else "elapsed")
		}
	}
	init { require(period.days in 1..366 && plannedStarts.size in 6..12) }

	data class Bucket(val index: Int, val start: Instant, val end: Instant, val temporalState: String)
	fun aggregation(valueMode: String) = TrendAggregation("auto", intervalSeconds, valueMode,
		period.from.toString(), period.until.toString(), asOf.toString(),
		"calendar-buckets-v2", intervalMonths, effectiveEnd.toString())
}
