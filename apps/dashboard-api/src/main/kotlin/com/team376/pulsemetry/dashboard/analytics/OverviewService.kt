package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.analytics.OverviewResponse.*
import com.team376.pulsemetry.dashboard.analytics.OverviewValues.Values
import com.team376.pulsemetry.dashboard.analytics.OverviewValues.value
import com.team376.pulsemetry.dashboard.analytics.OverviewValues.metric
import com.team376.pulsemetry.dashboard.analytics.OverviewValues.ratio
import com.team376.pulsemetry.dashboard.analytics.UsageAggregator.Axis
import com.team376.pulsemetry.dashboard.analytics.UsageAggregator.Side
import com.team376.pulsemetry.dashboard.organization.Organization
import com.team376.pulsemetry.dashboard.request.ComparedPeriod
import com.team376.pulsemetry.dashboard.store.StoreUnavailableException
import com.team376.pulsemetry.dashboard.store.StoreQueryRejectedException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.TransientDataAccessException
import org.springframework.dao.RecoverableDataAccessException
import java.math.BigDecimal
import java.util.UUID

/** 한 snapshot에서 독립 집계를 읽는다. 공통 frame 실패는 HTTP 오류로 전파한다. */
class OverviewService(private val frames: AnalyticsFrames, private val aggregator: UsageAggregator, private val references: SnapshotReferences) {
 private val log = LoggerFactory.getLogger(OverviewService::class.java)
 fun overview(organization: Organization, requestedBy: UUID, period: ComparedPeriod): OverviewResponse {
  val f = frames.frame(organization, requestedBy, period, usesComparison = true, snapshotId = null)
  val meta = frames.meta(f).let { Meta(it.organizationId, it.generatedAt, it.dataThrough, it.currency, it.startDate, it.endDate, it.timeZone, it.dayCount, it.dataState, it.currentCoverage) }
  val comparison = Comparison(period.mode.wire, period.previous?.startDate?.toString(), period.previous?.endDate?.toString(), f.previousCoverage)
  val errors = mutableListOf<SectionError>()
  fun <T> section(name: String, read: () -> T): T? {
   if (f.empty) return null
   return try { read() } catch (e: StoreUnavailableException) {
    log.warn("overview section={} snapshot={} unavailable", name, f.snapshot.snapshotId, e)
    errors.add(SectionError(name, "source_unavailable", true)); null
   } catch (e: StoreQueryRejectedException) {
    log.error("overview section={} snapshot={} query rejected", name, f.snapshot.snapshotId, e)
    errors.add(SectionError(name, "query_failed", false)); null
   } catch (e: DataAccessException) {
    // 팀 이름·제품 매핑은 같은 snapshot의 RDS 참조다. 일시 연결 장애만 해당 영역으로 제한한다.
    if (e !is TransientDataAccessException && e !is RecoverableDataAccessException && e !is DataAccessResourceFailureException) throw e
    log.warn("overview section={} snapshot={} reference unavailable", name, f.snapshot.snapshotId, e)
    errors.add(SectionError(name, "source_unavailable", true)); null
   }
  }
  val summary = section("summary") { summary(f) }
  val trend = section("trend") { trend(f) }
  val mix = section("modelMix") { modelMix(f) }
  val teams = section("teamUsage") { teamUsage(f) }
  return OverviewResponse(meta, comparison, summary, trend, mix, teams, errors)
 }
 private fun periodReasons(f: AnalyticsFrames.Frame) = f.periodReasons() + f.periodReasons(previous = true)
 private fun count(f: AnalyticsFrames.Frame, now: Value<Long>, before: Value<Long>?) = metric(now, before, f.period.mode.wire, periodReasons(f), Long::toBigDecimal, BigDecimal::toLong)
 private fun money(f: AnalyticsFrames.Frame, now: Value<String>, before: Value<String>?) = metric(now, before, f.period.mode.wire, periodReasons(f), String::toBigDecimal, Money::format)
 private fun values(f: AnalyticsFrames.Frame, t: UsageTotals, previous: Boolean = false) = Values(t, if (previous) f.previousComplete else f.currentComplete, f.pricingMixed, if (previous) "previous_period_incomplete" else "current_period_incomplete", f.periodReasons(previous))
 private fun summary(f: AnalyticsFrames.Frame): Summary {
  val now = values(f, aggregator.totals(f.snapshot, Side.CURRENT, Axis.ORGANIZATION).getValue(emptyList()))
  val before = if (f.comparable) values(f, aggregator.totals(f.snapshot, Side.PREVIOUS, Axis.ORGANIZATION).getValue(emptyList()), true) else null
  return Summary(count(f, now.users, before?.users), money(f, now.cost, before?.cost), count(f, now.sessions, before?.sessions),
   money(f, now.perUser, before?.perUser), count(f, now.tokens, before?.tokens), now.sessionsPerUser, now.perSession)
 }
 private fun trend(f: AnalyticsFrames.Frame): Trend {
  val days = aggregator.totals(f.snapshot, Side.CURRENT, Axis.DAY)
  return Trend("day", f.period.current.dates().map { date ->
   val observation = f.observation(date)
   val v = Values(days[listOf(date.toString())] ?: UsageTotals.EMPTY, observation == "complete", f.pricingMixed, detailReasons = f.dateReasons(date))
   TrendPoint(date.toString(), observation, if (observation == "unobserved") value(null, listOf("no_data") + f.dateReasons(date)) else v.cost,
    if (observation == "unobserved") value(null, listOf("no_data") + f.dateReasons(date)) else v.tokens)
  })
 }
 private fun modelMix(f: AnalyticsFrames.Frame): ModelMix {
  val models = aggregator.totals(f.snapshot, Side.CURRENT, Axis.MODEL).map { (key,t) -> key.single()!! to values(f,t) }
   .sortedWith(compareBy<Pair<String, Values>, BigDecimal?>(nullsLast(reverseOrder())) { it.second.cost.value?.toBigDecimal() }.thenBy { it.first })
  val costs = models.map { it.second.cost }
  val total = value(if (costs.any { it.value == null }) null else Money.format(costs.sumOf { it.value!!.toBigDecimal() }), costs.flatMap { it.reasons })
  return ModelMix(models.map { (id,v) -> ModelShare(id, ModelNames.displayName(id), v.cost, v.tokens,
   value(Money.perMillion(v.cost.value?.toBigDecimal(), v.tokens.value)?.let(Money::format), v.cost.reasons + v.tokens.reasons + if (v.tokens.value == 0L) listOf("zero_denominator") else emptyList()),
   ratio(v.cost, total, String::toBigDecimal, String::toBigDecimal, BigDecimal::toDouble)) })
 }
 private fun teamUsage(f: AnalyticsFrames.Frame): TeamUsage {
  val mapping = references.products(f.snapshot)
  val directory = references.teams(f.snapshot).associateBy { it.id.toString() }
  val current = aggregator.totals(f.snapshot, Side.CURRENT, Axis.TEAM)
  val previous = if (f.comparable) aggregator.totals(f.snapshot, Side.PREVIOUS, Axis.TEAM) else emptyMap()
  val products = aggregator.totals(f.snapshot, Side.CURRENT, Axis.TEAM_PRODUCT, products = mapping)
  fun productsOf(id: String?) = Products.refs(products.filter { it.key[0] == id && it.value.hasUsage }.map { it.key[1] ?: UsageAggregator.UNMAPPED_PRODUCT }, mapping)
  fun before(id: String?) = if (f.comparable) values(f, previous[listOf(id)] ?: UsageTotals.EMPTY, true).cost else null
  val ranked = current.filterKeys { it.single() != null }.entries.sortedWith(compareBy<Map.Entry<List<String?>,UsageTotals>,BigDecimal?>(nullsLast(reverseOrder())) { it.value.equivalentCost(f.pricingMixed) }.thenBy { it.key.single() })
  val teams = ranked.take(3).map { (key,t) -> val id = key.single()!!; val v = values(f,t)
   TopTeam(id, directory[id]?.name ?: id, productsOf(id), v.users, money(f,v.cost,before(id))) }
  val unassigned = current[listOf(null)]?.takeIf { it.hasUsage }?.let { val v = values(f,it); Unassigned(productsOf(null),v.users,money(f,v.cost,before(null))) }
  return TeamUsage("event_time", (directory.values.filter { !it.archived }.map { it.id.toString() } + ranked.map { it.key.single()!! }).toSet().size, teams, unassigned)
 }
}
