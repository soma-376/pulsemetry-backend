package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.request.DatePeriod
import com.team376.pulsemetry.dashboard.request.QueryReader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class TrendBucketPlanTest {
 private fun period(start: String, days: Int) = LocalDate.parse(start).let { DatePeriod(it, it.plusDays(days - 1L), QueryReader.SEOUL) }
 @Test fun everySupportedPeriodHasSixToTwelveContiguousBuckets() {
  for (start in listOf("2024-01-31", "2024-02-29", "2025-01-01", "2026-08-31")) for (days in 1..366) {
   val p = period(start, days); val plan = TrendBucketPlan(p, p.until)
   assertThat(plan.buckets.size).describedAs("%s %s일", start, days).isBetween(6, 12)
   assertThat(plan.buckets.first().start).isEqualTo(p.from)
   assertThat(plan.buckets.last().end).isEqualTo(p.until)
   assertThat(plan.buckets.map { it.temporalState }).containsOnly("elapsed")
   plan.buckets.zipWithNext().forEach { (a, next) -> assertThat(a.end).isEqualTo(next.start) }
  }
 }
 @Test fun policyThresholdsAreExplicit() {
  val cases = listOf(1 to 3, 2 to 6, 3 to 12, 6 to 12, 7 to 24, 12 to 24, 13 to 48, 24 to 48,
   25 to 72, 36 to 72, 37 to 168, 84 to 168, 85 to 336, 168 to 336)
  for ((days, hours) in cases) {
   val p = period("2026-01-01", days)
   assertThat(TrendBucketPlan(p, p.until).intervalSeconds).isEqualTo(hours * 3600L)
  }
  val p = period("2026-01-01", 169)
  assertThat(TrendBucketPlan(p, p.until).intervalMonths).isEqualTo(1)
 }
 @Test fun currentBoundaryHasNoFutureOrZeroLengthBucket() {
  val p = period("2026-11-05", 1)
  for (asOf in listOf(p.from.minusNanos(1), p.from)) assertThat(TrendBucketPlan(p, asOf).buckets).isEmpty()
  val exact = TrendBucketPlan(p, p.from.plusSeconds(10800))
  assertThat(exact.buckets).hasSize(1)
  assertThat(exact.buckets.single().temporalState).isEqualTo("elapsed")
  val after = TrendBucketPlan(p, p.from.plusSeconds(10800).plusNanos(1))
  assertThat(after.buckets).hasSize(2)
  assertThat(after.buckets.last().end).isEqualTo(after.asOf)
  assertThat(after.buckets.last().temporalState).isEqualTo("in_progress")
  assertThat(after.plannedStarts).hasSize(8)
  assertThat(after.intervalSeconds).isEqualTo(10800)
 }
 @Test fun calendarMonthsKeepOriginalDayAndMergeThirteenthRemainder() {
  val p = period("2024-01-31", 366); val plan = TrendBucketPlan(p, p.until)
  assertThat(plan.intervalSeconds).isNull()
  assertThat(plan.buckets[1].start).isEqualTo(LocalDate.parse("2024-02-29").atStartOfDay(QueryReader.SEOUL).toInstant())
  assertThat(plan.buckets[2].start).isEqualTo(LocalDate.parse("2024-03-31").atStartOfDay(QueryReader.SEOUL).toInstant())
  val extra = period("2025-01-01", 366); val merged = TrendBucketPlan(extra, extra.until)
  assertThat(merged.buckets).hasSize(12)
  assertThat(merged.buckets.last().end).isEqualTo(LocalDate.parse("2026-01-02").atStartOfDay(QueryReader.SEOUL).toInstant())
 }
}
