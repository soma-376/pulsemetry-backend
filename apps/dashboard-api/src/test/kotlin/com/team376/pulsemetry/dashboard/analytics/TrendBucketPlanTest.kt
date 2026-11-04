package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.request.DatePeriod
import com.team376.pulsemetry.dashboard.request.QueryReader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class TrendBucketPlanTest {
 private val start = LocalDate.parse("2026-01-02")
 @Test fun intervalsAndClippedBoundaries() {
  val cases = listOf(1 to 1, 2 to 1, 3 to 3, 7 to 3, 8 to 6, 15 to 6, 16 to 12,
   30 to 12, 31 to 24, 60 to 24, 61 to 72, 180 to 72, 181 to 168, 366 to 168)
  for ((days, hours) in cases) {
   val period = DatePeriod(start, start.plusDays(days - 1L), QueryReader.SEOUL)
   val plan = TrendBucketPlan(period, period.until)
   assertThat(plan.intervalSeconds).describedAs("%s일", days).isEqualTo(hours * 3600L)
   assertThat(plan.buckets).hasSize((days * 24 + hours - 1) / hours).hasSizeLessThanOrEqualTo(60)
   assertThat(plan.buckets.first().start).isEqualTo(period.from)
   assertThat(plan.buckets.last().end).isEqualTo(period.until)
   assertThat(plan.buckets.map { it.temporalState }).containsOnly("elapsed")
   plan.buckets.zipWithNext().forEach { (a, next) -> assertThat(a.end).isEqualTo(next.start) }
  }
  val clipped = TrendBucketPlan(DatePeriod(start, start.plusDays(60), QueryReader.SEOUL), start.atStartOfDay(QueryReader.SEOUL).toInstant())
  assertThat(clipped.buckets.last().end.epochSecond - clipped.buckets.last().start.epochSecond).isEqualTo(86400)
 }
 @Test fun todayKeeps24BucketsAtEveryBoundary() {
  val period = DatePeriod(start, start, QueryReader.SEOUL)
  for (asOf in listOf(period.from.minusNanos(1), period.from, period.from.plusSeconds(3600).minusNanos(1), period.from.plusSeconds(3600), period.until)) {
   val plan = TrendBucketPlan(period, asOf)
   assertThat(plan.buckets).hasSize(24)
   assertThat(plan.intervalSeconds).isEqualTo(3600)
  }
  val midnight = TrendBucketPlan(period, period.from)
  assertThat(midnight.buckets.first().temporalState).isEqualTo("in_progress")
  assertThat(midnight.buckets.drop(1).map { it.temporalState }).containsOnly("upcoming")
  val next = TrendBucketPlan(period, period.from.plusSeconds(3600))
  assertThat(next.buckets.take(2).map { it.temporalState }).containsExactly("elapsed", "in_progress")
 }
}
