package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.request.DatePeriod
import java.time.Duration
import java.time.Instant

/** 조회 시작 자정을 기준으로 전체 기간을 최대 60개로 나눈다. 현재 시각은 간격 선택에 영향을 주지 않는다. */
class TrendBucketPlan(val period: DatePeriod, val asOf: Instant) {
	val intervalSeconds: Long = INTERVALS.first { (Duration.between(period.from, period.until).seconds + it - 1) / it <= 60 }
	val buckets: List<Bucket> = buildList {
		var start = period.from
		while (start < period.until) {
			val end = minOf(start.plusSeconds(intervalSeconds), period.until)
			add(Bucket(size, start, end, when {
				asOf < start -> "upcoming"
				asOf < end -> "in_progress"
				else -> "elapsed"
			}))
			start = end
		}
	}
	data class Bucket(val index: Int, val start: Instant, val end: Instant, val temporalState: String) {
		val upcoming: Boolean get() = temporalState == "upcoming"
	}
	fun aggregation(valueMode: String) = TrendAggregation("auto", intervalSeconds, valueMode,
		period.from.toString(), period.until.toString(), asOf.toString())
	private companion object { val INTERVALS = listOf(3600L, 10800L, 21600L, 43200L, 86400L, 259200L, 604800L) }
}
