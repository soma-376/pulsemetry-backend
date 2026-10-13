package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.analytics.OverviewResponse.*
import java.math.BigDecimal
import java.math.MathContext

/** 완전성, 식별/단가/토큰 결손과 비교 사유를 한 곳에서 계산한다. */
object OverviewValues {
 fun <T> value(raw: T?, reasons: List<String> = emptyList()): Value<T> =
  Value(raw, if (raw == null) "unavailable" else if (reasons.isEmpty()) "complete" else "partial",
   reasons.distinct().ifEmpty { if (raw == null) listOf("no_data") else emptyList() })
 class Values(t: UsageTotals, complete: Boolean, mixed: Boolean, periodReason: String = "current_period_incomplete", detailReasons: List<String> = emptyList()) {
  private val zero = complete && !t.hasUsage
  private val period = if (complete) emptyList() else (listOf(periodReason) + detailReasons).distinct()
  val users = value(if (zero) 0L else t.activeUsers(), period + if (t.unidentifiedRows > 0) listOf("member_identity_missing") else emptyList())
  val sessions = value(if (zero) 0L else t.sessionCount(), period + if (t.sessionlessRows > 0) listOf("session_identity_missing") else emptyList())
  val cost = value((if (zero) BigDecimal.ZERO else t.equivalentCost(mixed))?.let(Money::format), period + buildList {
   if (t.unpricedRows > 0) add("pricing_missing")
   if (mixed && t.hasUsage) add("pricing_version_mixed")
  })
  val tokens = value(if (zero) 0L else t.apiTotal(), period + buildList {
   if (t.hasUsage && !t.semanticsUniform) add("token_semantics_incompatible")
   if (t.apiTotalMissingRows > 0) add("token_data_incomplete")
  })
  // 미식별 사용량을 식별 인원으로 나누면 모집단이 달라지므로 평균을 내지 않는다.
  private val userDenominator = if (t.unidentifiedRows > 0) value<Long>(null, users.reasons) else users
  val perUser = ratio(cost, userDenominator, String::toBigDecimal, Long::toBigDecimal, Money::format)
  val sessionsPerUser = ratio(sessions, userDenominator, Long::toBigDecimal, Long::toBigDecimal, BigDecimal::toDouble)
  val perSession = ratio(cost, sessions, String::toBigDecimal, Long::toBigDecimal, Money::format)
 }
 fun <A,B,R> ratio(a: Value<A>, b: Value<B>, toA: (A)->BigDecimal, toB: (B)->BigDecimal, convert: (BigDecimal)->R): Value<R> {
  val numerator = a.value?.let(toA); val denominator = b.value?.let(toB)
  val reasons = a.reasons + b.reasons + if (denominator?.signum() == 0) listOf("zero_denominator") else emptyList()
  return value(if (numerator == null || denominator == null || denominator.signum() == 0) null
   else convert(numerator.divide(denominator, MathContext.DECIMAL64)), reasons)
 }
 fun <T> metric(current: Value<T>, previous: Value<T>?, mode: String, periodReasons: List<String>, toDecimal: (T)->BigDecimal, convert: (BigDecimal)->T): Metric<T> {
  val reasons = (periodReasons + current.reasons + (previous?.reasons ?: emptyList())).distinct()
  val change = when {
   mode == "none" -> Change<T>("disabled", emptyList(), null, null, null, null)
   reasons.isNotEmpty() || current.value == null || previous?.value == null ->
    Change<T>("unavailable", reasons.ifEmpty { listOf("no_data") }, null, null, null, null)
   else -> {
    val now = toDecimal(current.value); val before = toDecimal(previous.value); val delta = now - before
    Change("available", emptyList(), previous.value, convert(delta),
     if (before.signum() == 0) { if (now.signum() == 0) 0.0 else null } else delta.divide(before, MathContext.DECIMAL64).toDouble() * 100,
     when { delta.signum() == 0 -> "unchanged"; before.signum() == 0 -> "new"; delta.signum() > 0 -> "increase"; else -> "decrease" })
   }
  }
  return Metric(current.value, current.status, current.reasons, change)
 }
}
