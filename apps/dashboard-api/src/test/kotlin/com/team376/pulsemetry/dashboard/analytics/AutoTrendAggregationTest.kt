package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.organization.Organization
import com.team376.pulsemetry.dashboard.overview.OverviewService
import com.team376.pulsemetry.dashboard.request.*
import com.team376.pulsemetry.dashboard.team.TeamsService
import com.team376.pulsemetry.dashboard.support.*
import com.team376.pulsemetry.dashboard.support.SourceFixtures.Event
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.Mockito
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** 문서 허브와 프론트가 공유하는 명시 이벤트를 실제 ClickHouse에 적재해 응답 전체와 대조한다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutoTrendAggregationTest {
 private val mapper = JsonMapper.builder().build()
 private val fixture by lazy { mapper.readTree(Path.of("../../docs/api/examples/auto-trend.fixture.json").toFile()) }
 private val asOf by lazy { Instant.parse(fixture.path("asOf").asString()) }
 private val assembly by lazy { SnapshotAssembly(clock = Clock.fixed(asOf, ZoneOffset.UTC),
  // 고정 계약 시각이 지난 뒤에도 실제 ClickHouse 시계의 TTL이 테스트 입력을 지우지 않게 한다.
  limits = SnapshotAssembly.DEFAULT_LIMITS.copy(purgeGrace = Duration.ofDays(3650))) }
 private val aggregator by lazy { UsageAggregator(assembly.clickHouse) }
 private val period = ComparedPeriod(DatePeriod(LocalDate.parse("2026-11-04"), LocalDate.parse("2026-11-04"), QueryReader.SEOUL), CompareMode.NONE)
 @BeforeAll fun schemas() { DashboardTestStores.ensureSchemas(); SourceFixtures.completeBackfill() }
 private fun json(value: Any): JsonNode = mapper.readTree(mapper.writeValueAsString(value))
 private fun nodes(node: JsonNode) = (0 until node.size()).map { node.get(it) }
 private fun event(node: JsonNode, team: UUID) = Event(
  name = node.path("id").asString(), sourceTime = Instant.parse(node.path("sourceTime").asString()), teamId = team,
  product = node.path("product").asString(), sessionId = node.path("sessionId").takeUnless { it.isNull }?.asString(),
  sessionIdNamespace = node.path("sessionNamespace").asString(),
  costEstimatedUsd = node.path("equivalentCostUsd").takeUnless { it.isNull }?.asString()?.toBigDecimal(),
  tokensInputUncached = node.path("totalTokens").asLong(), tokensOutput = 0, tokensTotalDerived = node.path("totalTokens").asLong(),
  semanticsProfile = if (node.path("product").asString() == "codex") "codex-responses-inclusive-v1" else "claude-code-exclusive-v1",
 )
 private data class Services(val org: Organization, val member: UUID, val team: UUID, val overview: OverviewService, val teams: TeamsService)
 private fun services(events: List<JsonNode>): Services {
  val tenant = DashboardTestStores.insertTenant()
  val team = SourceFixtures.insertTeam(tenant, "팀 A")
  SourceFixtures.insertEvents(tenant, *events.map { event(it, team) }.toTypedArray())
  val member = UUID.randomUUID()
  val snapshot = assembly.service.create(tenant, member, period, TrendBucketMode.AUTO)
  val refs = SnapshotReferences(assembly.clickHouse, DashboardTestStores.writer)
  val observed = refs.observedDates(snapshot)
  val coverage = Coverage.of(period.current.dates(), observed, emptySet())
  val org = Organization(tenant, "조직", "Asia/Seoul")
  val frame = AnalyticsFrames.Frame(org, period, snapshot, IngestStatusReader.History(null), observed, emptySet(), coverage, null,
   if (observed.isEmpty()) "never_observed" else "partial", false, asOf)
  val frames = Mockito.mock(AnalyticsFrames::class.java, Mockito.CALLS_REAL_METHODS)
  Mockito.doReturn(frame).`when`(frames).frame(org, member, period, true, null, TrendBucketMode.AUTO)
  return Services(org, member, team, OverviewService(frames, aggregator, refs), TeamsService(frames, aggregator, refs))
 }
 @Test fun sharedFixtureMatchesOverviewAndTeamValuesExactly() {
  val s = services(nodes(fixture.path("events")))
  val overview = json(s.overview.overview(s.org, s.member, period, TrendBucketMode.AUTO))
  val team = json(s.teams.team(s.org, s.member, TeamsService.TeamKey.Team(s.team), period, null, TrendBucketMode.AUTO))
  assertThat(overview.path("trend")).isEqualTo(fixture.at("/expected/overviewTrend"))
  assertThat(team.at("/team/trend")).isEqualTo(fixture.at("/expected/teamTrend"))
  assertThat(overview.at("/meta/trendAggregation")).isEqualTo(fixture.at("/expected/overviewMeta/trendAggregation"))
  assertThat(overview.at("/meta/collectionPolicy")).isEqualTo(fixture.at("/expected/overviewMeta/collectionPolicy"))
  assertThat(overview.at("/meta/snapshotId").asString()).isNotEmpty()
  assertThat(overview.at("/summary/equivalentCostUsd/value").asString()).isEqualTo("0.300000000003")
  assertThat(team.at("/team/metrics/sessionCount/value").asLong()).isEqualTo(4)
  assertThat(team.at("/team/trend/3/cumulativeTotalTokens/value")).isEqualTo(team.at("/team/metrics/totalTokens/value"))
 }
 @Test fun pricingAndSessionFailuresStayIndependentAndEmptyInputIsNotZero() {
  for (case in nodes(fixture.path("metricCases"))) {
   val s = services(nodes(case.path("events")))
   val team = json(s.teams.team(s.org, s.member, TeamsService.TeamKey.Team(s.team), period, null, TrendBucketMode.AUTO))
   val point = team.at("/team/trend/3")
   if (case.has("expectedFinalCumulative")) {
    val expected = case.path("expectedFinalCumulative")
    for (name in expected.propertyNames()) assertThat(point.path(name)).describedAs("%s/%s", case.path("name"), name).isEqualTo(expected.path(name))
   } else {
    assertThat(point.at("/cumulativeEquivalentCostUsd/value").isNull).isTrue()
    assertThat(point.at("/cumulativeEquivalentCostUsd/reasons/0").asString()).isEqualTo("no_data")
    assertThat(point.path("usageRows").asLong()).isZero()
    assertThat(team.at("/team/trend").size()).isEqualTo(4)
    val overview = json(s.overview.overview(s.org, s.member, period, TrendBucketMode.AUTO))
    assertThat(overview.at("/trend/points").size()).isEqualTo(4)
   }
  }
 }
 @Test fun preciseBucketBoundaryAndSemanticUnionNeverForgetEarlierDefects() {
  val tenant = UUID.randomUUID(); val team = UUID.randomUUID(); val start = period.current.from
  val events = listOf(
   Event("before", start.minusNanos(1), teamId = team),
   Event("start", start, teamId = team, costEstimatedUsd = BigDecimal("1")),
   Event("last", start.plusSeconds(10800).minusNanos(1), teamId = team, costEstimatedUsd = BigDecimal("2")),
   Event("next", start.plusSeconds(10800), teamId = team, costEstimatedUsd = BigDecimal("3"), semanticsProfile = "other-v1"),
   Event("after", start.plusSeconds(10800).plusNanos(1), teamId = team, costEstimatedUsd = BigDecimal("4"), sessionId = null),
   Event("valid-later", start.plusSeconds(21600), teamId = team, costEstimatedUsd = BigDecimal("5")),
   Event("asof", asOf, teamId = team), Event("end", period.current.until, teamId = team))
  SourceFixtures.insertEvents(tenant, *events.toTypedArray())
  val snapshot = assembly.service.create(tenant, UUID.randomUUID(), period, TrendBucketMode.AUTO)
  val plan = TrendBucketPlan(period.current, snapshot.asOf)
  val totals = aggregator.bucketTotals(snapshot, plan, true)
  assertThat(totals.mapValues { it.value.usageRows }).containsAllEntriesOf(mapOf(listOf(team.toString(), "0") to 2L, listOf(team.toString(), "1") to 2L, listOf(team.toString(), "2") to 1L))
  val first = aggregator.firstSessionBuckets(snapshot, plan)
  val cumulative = TrendCumulativeTotals()
  for (bucket in plan.buckets) cumulative.add(totals[listOf(team.toString(), bucket.index.toString())] ?: UsageTotals.EMPTY, first[team.toString() to bucket.index] ?: 0)
  val actual = UsageValues.recorded(cumulative.totals)
  assertThat(actual.cost.value).isEqualTo("15.000000")
  assertThat(actual.cost.status).isEqualTo("complete")
  assertThat(actual.tokens.value).isNull()
  assertThat(actual.tokens.reasons).containsExactly("token_semantics_incompatible")
  assertThat(actual.sessions.reasons).containsExactly("session_identity_missing")
  assertThat(cumulative.totals.semanticsGroups).hasSize(2)
  val periodTotal = aggregator.totals(snapshot, UsageAggregator.Side.CURRENT, UsageAggregator.Axis.ORGANIZATION).getValue(emptyList())
  assertThat(actual.cost).isEqualTo(UsageValues.recorded(periodTotal).cost)
  assertThat(actual.tokens).isEqualTo(UsageValues.recorded(periodTotal).tokens)
 }
 @Test fun calendarMonthBoundariesKeepNanosecondsAndCountSessionsOnlyOnce() {
  val tenant = UUID.randomUUID(); val team = UUID.randomUUID()
  val selected = ComparedPeriod(DatePeriod(LocalDate.parse("2024-01-31"), LocalDate.parse("2024-07-30"), QueryReader.SEOUL), CompareMode.NONE)
  val boundary = LocalDate.parse("2024-02-29").atStartOfDay(QueryReader.SEOUL).toInstant()
  SourceFixtures.insertEvents(tenant,
   Event("before-month", boundary.minusNanos(1), teamId = team, sessionId = "same", costEstimatedUsd = BigDecimal("0.1")),
   Event("at-month", boundary, teamId = team, sessionId = "same", costEstimatedUsd = BigDecimal("0.2")),
   Event("after-month", boundary.plusNanos(1), teamId = team, sessionId = "other", costEstimatedUsd = BigDecimal("0.3")),
   Event("excluded-end", selected.current.until, teamId = team))
  val snapshot = assembly.service.create(tenant, UUID.randomUUID(), selected, TrendBucketMode.AUTO)
  val plan = TrendBucketPlan(selected.current, snapshot.asOf)
  val totals = aggregator.bucketTotals(snapshot, plan, true)
  assertThat(plan.intervalMonths).isEqualTo(1)
  assertThat(totals.mapValues { it.value.usageRows }).containsExactlyInAnyOrderEntriesOf(mapOf(listOf(team.toString(), "0") to 1L, listOf(team.toString(), "1") to 2L))
  val first = aggregator.firstSessionBuckets(snapshot, plan)
  assertThat(first).containsExactlyInAnyOrderEntriesOf(mapOf((team.toString() to 0) to 1L, (team.toString() to 1) to 1L))
  val cumulative = TrendCumulativeTotals()
  for (bucket in plan.buckets) cumulative.add(totals[listOf(team.toString(), bucket.index.toString())] ?: UsageTotals.EMPTY, first[team.toString() to bucket.index] ?: 0)
  val whole = aggregator.totals(snapshot, UsageAggregator.Side.CURRENT, UsageAggregator.Axis.ORGANIZATION).getValue(emptyList())
  assertThat(UsageValues.recorded(cumulative.totals).cost).isEqualTo(UsageValues.recorded(whole).cost)
 }
 @Test fun firstSessionsAreDistinctPerTeamProductAndNamespaceAndQueriesAreBatched() {
  val tenant = UUID.randomUUID(); val team = UUID.randomUUID(); val other = UUID.randomUUID(); val start = period.current.from
  SourceFixtures.insertEvents(tenant,
   Event("one", start, teamId = team, sessionId = "same"),
   Event("repeat", start.plusSeconds(10800), teamId = team, sessionId = "same"),
   Event("product", start.plusSeconds(10800), teamId = team, sessionId = "same", product = "codex"),
   Event("namespace", start.plusSeconds(21600), teamId = team, sessionId = "same", sessionIdNamespace = "other"),
   Event("team", start.plusSeconds(10800), teamId = other, sessionId = "same"),
   Event("unassigned", start.plusSeconds(10800), sessionId = "same"))
  val snapshot = assembly.service.create(tenant, UUID.randomUUID(), period, TrendBucketMode.AUTO)
  val client = Mockito.spy(assembly.clickHouse)
  val batched = UsageAggregator(client)
  for (days in listOf(1L, 366L)) {
   Mockito.clearInvocations(client)
   val plan = TrendBucketPlan(DatePeriod(period.current.startDate, period.current.startDate.plusDays(days - 1), QueryReader.SEOUL), snapshot.asOf)
   batched.bucketTotals(snapshot, plan, true)
   val first = batched.firstSessionBuckets(snapshot, plan)
   assertThat(first.filterKeys { it.first == team.toString() }.values.sum()).isEqualTo(3)
   assertThat(first.filterKeys { it.first == other.toString() }.values.sum()).isEqualTo(1)
   assertThat(first.filterKeys { it.first == null }.values.sum()).isEqualTo(1)
   assertThat(Mockito.mockingDetails(client).invocations.filter { it.method.name == "query" }).hasSize(2)
  }
 }
}
