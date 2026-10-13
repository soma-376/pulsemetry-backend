package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.analytics.OverviewValues.Values
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class OverviewValuesTest {
 private val sample = UsageTotals.EMPTY.copy(usageRows=2, identifiedUsers=1, identifiedSessions=2,
  totalDerived=UsageTotals.Component(100,0), semanticsProfiles=1, cost=BigDecimal("12"))
 @Test fun `identified users stay partial but average cannot mix populations`() {
  val v=Values(sample.copy(unidentifiedRows=1),true,false)
  assertThat(v.users.value).isEqualTo(1)
  assertThat(v.users.status).isEqualTo("partial")
  assertThat(v.users.reasons).containsExactly("member_identity_missing")
  assertThat(v.cost.status).isEqualTo("complete")
  assertThat(v.perUser.value).isNull()
  assertThat(v.perUser.reasons).contains("member_identity_missing")
  assertThat(v.sessionsPerUser.value).isNull()
 }
 @Test fun `zero usage in complete period is zero with undefined averages`() {
  val v=Values(UsageTotals.EMPTY,true,false)
  assertThat(v.users.value).isZero();assertThat(v.sessions.value).isZero();assertThat(v.tokens.value).isZero()
  assertThat(v.cost.value).isEqualTo("0.000000")
  assertThat(v.perUser.value).isNull();assertThat(v.perUser.reasons).containsExactly("zero_denominator")
  assertThat(Values(UsageTotals.EMPTY,false,false).cost.value).isNull()
 }
 @Test fun `metric reasons are independent and periods add their own reasons`() {
  val v=Values(sample.copy(sessionlessRows=1,unpricedRows=1,apiTotalMissingRows=1),false,true)
  assertThat(v.users.status).isEqualTo("partial")
  assertThat(v.sessions.reasons).containsExactly("current_period_incomplete","session_identity_missing")
  assertThat(v.cost.reasons).containsExactly("current_period_incomplete","pricing_missing","pricing_version_mixed")
  assertThat(v.tokens.reasons).containsExactly("current_period_incomplete","token_data_incomplete")
  val other=Values(sample.copy(semanticsProfiles=2),true,false)
  assertThat(other.tokens.value).isNull();assertThat(other.tokens.reasons).containsExactly("token_semantics_incompatible")
 }
 private fun delta(now:Long,before:Long,mode:String="prev_week")=OverviewValues.metric(OverviewValues.value(now),OverviewValues.value(before),mode,emptyList(),Long::toBigDecimal,BigDecimal::toLong)
 @Test fun `zero baseline new unchanged negative and disabled comparison`() {
  assertThat(delta(5,0).comparison.change).isEqualTo("new");assertThat(delta(5,0).comparison.percentChange).isNull()
  assertThat(delta(0,0).comparison.change).isEqualTo("unchanged");assertThat(delta(0,0).comparison.percentChange).isZero()
  assertThat(delta(5,10).comparison.absoluteChange).isEqualTo(-5);assertThat(delta(5,10).comparison.percentChange).isEqualTo(-50.0)
  assertThat(delta(5,10,"none").comparison.status).isEqualTo("disabled");assertThat(delta(5,10,"none").comparison.previousValue).isNull()
 }
 @Test fun `comparison hides previous and deltas when either period lacks evidence`() {
  val result=OverviewValues.metric(OverviewValues.value("5.000000"),OverviewValues.value("2.000000"),"prev_week",listOf("previous_period_incomplete"),String::toBigDecimal,Money::format)
  assertThat(result.value).isEqualTo("5.000000");assertThat(result.status).isEqualTo("complete")
  assertThat(result.comparison.status).isEqualTo("unavailable");assertThat(result.comparison.previousValue).isNull()
  assertThat(result.comparison.absoluteChange).isNull();assertThat(result.comparison.percentChange).isNull()
 }
}
