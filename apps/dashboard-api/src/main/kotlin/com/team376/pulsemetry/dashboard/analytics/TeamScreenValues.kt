package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.analytics.OverviewResponse.Value
import java.math.BigDecimal
import java.time.LocalDate

/** 팀 화면의 파생값과 비교 판정은 개요와 같은 근거를 사용한다. */
object TeamScreenValues {
 private fun values(frame: AnalyticsFrames.Frame, totals: UsageTotals, previous: Boolean = false) = OverviewValues.Values(
  totals, if (previous) frame.previousComplete else frame.currentComplete, frame.pricingMixed,
  if (previous) "previous_period_incomplete" else "current_period_incomplete", frame.periodReasons(previous),
 )
 fun metrics(frame: AnalyticsFrames.Frame, totals: UsageTotals, previous: UsageTotals?): TeamMetrics {
  val now = values(frame, totals)
  val before = previous?.let { values(frame, it, true) }
  val reasons = frame.periodReasons() + frame.periodReasons(true)
  val mode = frame.period.mode.wire
  fun money(v: Value<String>, p: Value<String>?) = OverviewValues.metric(v,p,mode,reasons,String::toBigDecimal,Money::format)
  fun count(v: Value<Long>, p: Value<Long>?) = OverviewValues.metric(v,p,mode,reasons,Long::toBigDecimal,BigDecimal::toLong)
  val users = if (totals.unidentifiedRows > 0) OverviewValues.value<Long>(null, now.users.reasons) else now.users
  return TeamMetrics(now.users, money(now.cost,before?.cost), count(now.tokens,before?.tokens), count(now.sessions,before?.sessions),
   money(now.perUser,before?.perUser),now.perSession,
   OverviewValues.ratio(now.tokens,users,Long::toBigDecimal,Long::toBigDecimal,BigDecimal::toDouble), now.sessionsPerUser,
   OverviewValues.ratio(now.tokens,now.sessions,Long::toBigDecimal,Long::toBigDecimal,BigDecimal::toDouble),
   OverviewValues.ratio(now.cost,now.tokens,String::toBigDecimal,Long::toBigDecimal) { Money.format(it * BigDecimal(1_000_000)) }, now.knownCost, now.pricedUsageRows, now.unpricedUsageRows)
 }
 fun user(frame: AnalyticsFrames.Frame, totals: UsageTotals): TeamUserUsage {
  val v = values(frame,totals)
  return TeamUserUsage(v.sessions,v.tokens,v.cost)
 }
 fun summary(frame: AnalyticsFrames.Frame, totals: UsageTotals): TeamSummaryUsage {
  val v = values(frame,totals)
  return TeamSummaryUsage(v.users,v.sessions,v.tokens,v.cost)
 }
 fun cache(frame: AnalyticsFrames.Frame, totals: UsageTotals): Value<Double> {
  val ratio = totals.cacheHitRatio()
  val reasons = frame.periodReasons() + buildList {
   if (ratio == null) add("cache_data_unavailable")
   if (totals.hasUsage && !totals.semanticsUniform) add("token_semantics_incompatible")
   if (totals.cacheEligibleInput() == 0L) add("zero_denominator")
  }
  return OverviewValues.value(ratio, reasons)
 }
 fun model(frame: AnalyticsFrames.Frame, modelId: String, totals: UsageTotals): TeamModelUsage {
  val v = values(frame, totals)
  val reasons = (v.cost.reasons + v.tokens.reasons).distinct()
  return TeamModelUsage(modelId, ModelNames.displayName(modelId), v.cost.value, v.tokens.value,
   status(listOf(v.cost.value, v.tokens.value), reasons), reasons)
 }
 fun scatter(model: TeamModelUsage, usingTeamCount: Long?): ScatterModel {
  val reasons = (model.reasons + if (usingTeamCount == null) listOf("team_count_unavailable") else emptyList()).distinct()
  val status = if (model.equivalentCostUsd == null || model.totalTokens == null) "unavailable"
   else if (reasons.isNotEmpty()) "partial" else "complete"
  return ScatterModel(model.modelId, model.displayName, model.equivalentCostUsd, model.totalTokens, usingTeamCount, status, reasons)
 }
 fun point(frame: AnalyticsFrames.Frame, date: LocalDate, totals: UsageTotals, cumulativeSessions: Long?, cumulativeReasons: List<String>): TeamTrendPoint {
  val v = OverviewValues.Values(totals, date in frame.completeDates, frame.pricingMixed, detailReasons = frame.dateReasons(date))
  val reasons = (v.cost.reasons + v.tokens.reasons + cumulativeReasons).distinct()
  return TeamTrendPoint(date.toString(), frame.observation(date), v.cost.value, v.tokens.value, cumulativeSessions,
   status(listOf(v.cost.value, v.tokens.value, cumulativeSessions), reasons), reasons, v.knownCost.value, v.pricedUsageRows, v.unpricedUsageRows)
 }
 private fun status(values: List<Any?>, reasons: List<String>): String = when {
  values.all { it == null } -> "unavailable"
  values.any { it == null } || reasons.isNotEmpty() -> "partial"
  else -> "complete"
 }
}
