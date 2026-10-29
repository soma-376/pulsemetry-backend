package com.team376.pulsemetry.dashboard.analytics

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class KnownUsageCostTest {
 private val totals = UsageTotals.EMPTY.copy(usageRows = 3, unpricedRows = 1,
  identifiedUsers = 1, identifiedSessions = 2, cost = BigDecimal("12.3456789"))

 @Test fun `priced subtotal is separate from unavailable total and averages`() {
  val value = OverviewValues.Values(totals, true)
  assertThat(value.cost.value).isNull()
  assertThat(value.perUser.value).isNull()
  assertThat(value.perSession.value).isNull()
  assertThat(value.knownCost.value).isEqualTo(Money.format(totals.cost!!))
  assertThat(value.knownCost.status).isEqualTo("partial")
  assertThat(value.knownCost.reasons).containsExactly("pricing_missing")
  assertThat(value.pricedUsageRows).isEqualTo(2)
  assertThat(value.unpricedUsageRows).isEqualTo(1)
 }

 @Test fun `all unpriced rows have no known subtotal`() {
  val value = OverviewValues.Values(totals.copy(unpricedRows = 3, cost = null), true)
  assertThat(value.knownCost.value).isNull()
  assertThat(value.knownCost.status).isEqualTo("unavailable")
  assertThat(value.pricedUsageRows).isZero()
 }

 @Test fun `stored priced rows remain available without a catalog identity`() {
  val value = OverviewValues.Values(totals, true)
  assertThat(value.knownCost.value).isEqualTo(Money.format(totals.cost!!))
  assertThat(value.knownCost.reasons).containsExactly("pricing_missing")
 }

 @Test fun `unknown days stay null while verified empty days are zero`() {
  val missing = OverviewValues.Values(UsageTotals.EMPTY, false)
  val empty = OverviewValues.Values(UsageTotals.EMPTY, true)
  assertThat(missing.knownCost.value).isNull()
  assertThat(empty.knownCost.value).isEqualTo("0.000000")
  assertThat(empty.knownCost.status).isEqualTo("complete")
 }

 @Test fun `an observed actual zero is retained with missing pricing warning`() {
  val value = OverviewValues.Values(totals.copy(cost = BigDecimal.ZERO), true)
  assertThat(value.knownCost.value).isEqualTo("0.000000")
  assertThat(value.knownCost.status).isEqualTo("partial")
  assertThat(value.cost.value).isNull()
 }

 @Test fun `historical partial coverage and missing price are distinct reasons`() {
  val value = OverviewValues.Values(totals, false, detailReasons = listOf("no_eligible_installations"))
  assertThat(value.knownCost.value).isNotNull()
  assertThat(value.knownCost.reasons).containsExactly("current_period_incomplete", "no_eligible_installations", "pricing_missing")
 }
}
