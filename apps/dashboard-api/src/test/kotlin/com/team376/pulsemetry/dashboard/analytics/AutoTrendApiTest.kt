package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.authentication.DashboardPrincipal
import com.team376.pulsemetry.dashboard.authentication.Role
import com.team376.pulsemetry.dashboard.request.QueryReader
import com.team376.pulsemetry.dashboard.support.*
import com.team376.pulsemetry.dashboard.support.SourceFixtures.Event
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class AutoTrendApiTest : AbstractDashboardApiTest() {
 @BeforeEach fun backfill() = SourceFixtures.completeBackfill()
 private fun get(tenant: UUID, route: String, query: String, expectedStatus: Int = 200): JsonNode {
  val response = http.send("/api/v1/organizations/$tenant/analytics/$route?$query",
   headers = mapOf("Authorization" to TestDashboardAuthenticator.header(DashboardPrincipal(tenant, UUID.randomUUID(), Role.ADMIN))))
  assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(expectedStatus)
  return DashboardHttp.json(response)
 }
 @Test fun autoOverviewListDetailUsersAndPaginationKeepTheirContractAndValues() {
  val tenant = DashboardTestStores.insertTenant()
  val team = SourceFixtures.insertTeam(tenant, "첫 팀")
  val second = SourceFixtures.insertTeam(tenant, "둘째 팀")
  val member = SourceFixtures.insertMember(tenant, "auto@example.test")
  val date = LocalDate.parse("2026-09-24")
  val start = date.atStartOfDay(QueryReader.SEOUL).toInstant()
  SourceFixtures.insertEvents(tenant,
   Event("first", start.plusSeconds(60), teamId = team, memberId = member, sessionId = "same", costEstimatedUsd = BigDecimal("1.000000000001")),
   Event("second", start.plusSeconds(7200), teamId = team, memberId = member, sessionId = "same", costEstimatedUsd = BigDecimal("2")),
   Event("other", start.plusSeconds(10800), teamId = second, memberId = member, costEstimatedUsd = BigDecimal("0.5")))
  val query = "startDate=$date&endDate=$date&compare=none"
  val overview = get(tenant, "overview", "$query&bucket=auto")
  assertThat(overview.at("/meta/trendAggregation/valueMode").asString()).isEqualTo("interval")
  assertThat(overview.at("/trend/points").size()).isEqualTo(8)
  assertThat(overview.at("/trend/points/0").has("date")).isFalse()
  assertThat(overview.at("/summary/equivalentCostUsd/value").asString()).isEqualTo("3.500000000001")
  assertThat(overview.at("/summary/equivalentCostUsd/status").asString()).isEqualTo("complete")
  val list = get(tenant, "teams", "$query&bucket=auto&limit=1")
  val snapshot = list.at("/meta/snapshotId").asString()
  val detail = get(tenant, "teams/$team", "$query&bucket=auto&snapshotId=$snapshot")
  assertThat(detail.at("/meta/trendAggregation")).isEqualTo(list.at("/meta/trendAggregation"))
  assertThat(detail.at("/team/trend")).isEqualTo(list.at("/teams/items/0/trend"))
  assertThat(detail.at("/team/trend/7/cumulativeEquivalentCostUsd/value")).isEqualTo(detail.at("/team/metrics/equivalentCostUsd/value"))
  assertThat(detail.at("/team/trend/7/cumulativeSessionCount/value").asLong()).isEqualTo(1)
  assertThat(detail.at("/team/trend/1/usageRows").asLong()).isZero()
  assertThat(detail.at("/team/trend/1/cumulativeEquivalentCostUsd/value").asString()).isEqualTo("3.000000000001")
  val users = get(tenant, "teams/$team/users", "$query&snapshotId=$snapshot")
  assertThat(users.at("/meta/trendAggregation")).isEqualTo(list.at("/meta/trendAggregation"))
  assertThat(users.at("/meta/collectionPolicy")).isEqualTo(list.at("/meta/collectionPolicy"))
  assertThat(users.at("/users/items/0/usage/equivalentCostUsd/value")).isEqualTo(detail.at("/team/metrics/equivalentCostUsd/value"))
  val next = get(tenant, "teams", "$query&bucket=auto&limit=1&offset=1&snapshotId=$snapshot")
  assertThat(next.at("/meta")).isEqualTo(list.at("/meta"))
  assertThat(next.at("/teams/items/0/teamId").asString()).isEqualTo(second.toString())
  get(tenant, "teams/$team", "$query&bucket=day&snapshotId=$snapshot", 409)
  get(tenant, "teams", "$query&snapshotId=$snapshot", 409)
  val day = get(tenant, "overview", query)
  val explicitDay = get(tenant, "overview", "$query&bucket=day")
  assertThat(day.path("trend")).isEqualTo(explicitDay.path("trend"))
  assertThat(day.at("/meta").has("trendAggregation")).isFalse()
  assertThat(day.at("/meta").has("collectionPolicy")).isFalse()
  assertThat(day.at("/meta").has("snapshotId")).isFalse()
  for (route in listOf("overview", "teams", "teams/$team")) get(tenant, route, "$query&bucket=hour", 400)
 }
 @Test fun emptyTodayOnlyHasElapsedAndCurrentBucketsWithoutInventingZeros() {
  val tenant = DashboardTestStores.insertTenant()
  val today = LocalDate.now(QueryReader.SEOUL)
  val body = get(tenant, "overview", "startDate=$today&endDate=$today&compare=none&bucket=auto")
  val points = body.at("/trend/points")
  assertThat(points.size()).isBetween(0, 8)
  val states = (0 until points.size()).map { points.get(it).path("temporalState").asString() }
  assertThat(states.count { it == "in_progress" }).isLessThanOrEqualTo(1)
  assertThat(states).doesNotContain("upcoming")
  for (i in 0 until points.size()) {
   val point = points.get(i)
   assertThat(point.at("/equivalentCostUsd/value").isNull).isTrue()
   assertThat(point.at("/equivalentCostUsd/reasons/0").asString()).isEqualTo(if (states[i] == "upcoming") "upcoming" else "no_data")
   assertThat(point.path("usageRows").isNull).isEqualTo(states[i] == "upcoming")
  }
 }
}
