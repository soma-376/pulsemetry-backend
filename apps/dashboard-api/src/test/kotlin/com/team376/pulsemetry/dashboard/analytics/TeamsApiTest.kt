package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.authentication.DashboardPrincipal
import com.team376.pulsemetry.dashboard.authentication.Role
import com.team376.pulsemetry.dashboard.request.QueryReader
import com.team376.pulsemetry.dashboard.support.AbstractDashboardApiTest
import com.team376.pulsemetry.dashboard.support.DashboardHttp
import com.team376.pulsemetry.dashboard.support.DashboardTestStores
import com.team376.pulsemetry.dashboard.support.JsonStructure
import com.team376.pulsemetry.dashboard.support.SourceFixtures
import com.team376.pulsemetry.dashboard.support.SourceFixtures.Event
import com.team376.pulsemetry.dashboard.support.TestDashboardAuthenticator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.net.URLEncoder
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

/**
 * 팀 분석 조회를 HTTP 끝까지 (팀 분석 명세 · 사례 16). 기대값은 명세의 집계 기준·수용 기준 문장에서 쓴다.
 */
class TeamsApiTest : AbstractDashboardApiTest() {

	@BeforeEach
	fun backfillDone() {
		SourceFixtures.completeBackfill()
	}

	private val week = "startDate=2026-09-07&endDate=2026-09-13"

	private fun kst(dateTime: String): Instant = LocalDateTime.parse(dateTime).atZone(QueryReader.SEOUL).toInstant()

	private fun get(tenant: UUID, path: String, role: Role = Role.ADMIN): HttpResponse<String> = http.send(
		"/api/v1/organizations/$tenant$path",
		headers = mapOf("Authorization" to TestDashboardAuthenticator.header(DashboardPrincipal(tenant, UUID.randomUUID(), role))),
	)

	private fun ok(tenant: UUID, path: String): JsonNode {
		val response = get(tenant, path)
		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200)
		return DashboardHttp.json(response)
	}

	private fun JsonNode.list(): List<JsonNode> = (0 until size()).map { get(it) }

	private fun money(node: JsonNode): BigDecimal = BigDecimal(node.asString())

	private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)

	private data class Org(val tenant: UUID, val teams: Map<String, UUID>, val members: Map<String, UUID>)

	/** 팀 A 40 · B 30 · C 20 · D 10, 미배정 5, 사용 없는 팀 E. 구성원은 팀마다 둘. */
	private fun seed(): Org {
		val tenant = DashboardTestStores.insertTenant()
		SourceFixtures.setSummary(tenant, firstReceivedAt = kst("2026-09-01T00:00:00"))
		val teams = listOf("A", "B", "C", "D", "E").associateWith { SourceFixtures.insertTeam(tenant, "팀 $it") }
		val members = teams.keys.flatMap { t -> listOf("$t-1", "$t-2") }.associateWith { SourceFixtures.insertMember(tenant, "${it.lowercase()}@example.test") }
		val costs = mapOf("A" to "40", "B" to "30", "C" to "20", "D" to "10")
		val events = costs.flatMap { (team, cost) ->
			val half = BigDecimal(cost).divide(BigDecimal(2))
			listOf(1, 2).map { i ->
				Event(
					"$tenant-$team-$i", kst("2026-09-0${7 + i}T10:00:00"), teamId = teams.getValue(team), memberId = members.getValue("$team-$i"),
					sessionId = "$team-session-$i", model = if (i == 1) "model-x" else "model-y", costEstimatedUsd = half, pricingVersion = "v1",
				)
			}
		} + Event("$tenant-unassigned", kst("2026-09-09T10:00:00"), costEstimatedUsd = BigDecimal("5"), pricingVersion = "v1")
		SourceFixtures.insertEvents(tenant, *events.toTypedArray())
		return Org(tenant, teams, members)
	}

	@Test
	@DisplayName("목록 — 비용 내림차순, 사용이 있는 실제 팀만 세고 미배정은 따로, 팀 합 + 미배정 = 조직 합계")
	fun listRanksAndTotals() {
		val org = seed()

		val body = ok(org.tenant, "/analytics/teams?$week")

		val items = body.at("/teams/items").list()
		assertThat(items.map { it.path("teamName").asString() }).containsExactly("팀 A", "팀 B", "팀 C", "팀 D")
		assertThat(body.at("/teams/totalCount").asInt()).isEqualTo(4)
		assertThat(body.at("/teams/nextCursor").isNull).isTrue()
		assertThat(body.at("/unassigned/teamId").isNull).isTrue()
		assertThat(body.at("/unassigned/teamName").asString()).isEqualTo("미배정")
		val teamSum = items.map { money(it.at("/current/equivalentCostUsd")) }.fold(BigDecimal.ZERO, BigDecimal::add) + money(body.at("/unassigned/current/equivalentCostUsd"))
		assertThat(teamSum).isEqualByComparingTo(money(body.at("/totals/current/equivalentCostUsd"))).isEqualByComparingTo("105")
		assertThat(body.at("/meta/snapshotId").asString()).hasSize(22)
		assertThat(body.at("/sort").asString()).isEqualTo("cost")
		assertThat(body.at("/attributionBasis").asString()).isEqualTo("event_time")
		assertThat(items[0].at("/trend").list().map { it.path("cumulativeSessionCount").isNull }).containsOnly(true).hasSize(7)
		assertThat(items[0].at("/modelMix/data/sessionMixAvailable").asBoolean()).isFalse()
		assertThat(items[0].at("/modelMix/data/sessionMixReason").asString()).isEqualTo("multi_model_sessions")
		// 조직 합계는 개요와 같은 계산기에서 나온다.
		val overview = ok(org.tenant, "/analytics/overview?$week")
		assertThat(money(overview.at("/usage/current/equivalentCostUsd"))).isEqualByComparingTo(money(body.at("/totals/current/equivalentCostUsd")))
	}

	@Test
	@DisplayName("페이지 — 같은 snapshot 의 페이지 사이에 중복·누락이 없고, 다른 정렬의 cursor 는 400")
	fun paging() {
		val org = seed()

		val first = ok(org.tenant, "/analytics/teams?$week&limit=3")
		val snapshotId = first.at("/meta/snapshotId").asString()
		val cursor = first.at("/teams/nextCursor").asString()
		val second = ok(org.tenant, "/analytics/teams?$week&limit=3&snapshotId=$snapshotId&cursor=$cursor")

		val names = (first.at("/teams/items").list() + second.at("/teams/items").list()).map { it.path("teamName").asString() }
		assertThat(names).containsExactly("팀 A", "팀 B", "팀 C", "팀 D")
		assertThat(second.at("/meta/snapshotId").asString()).isEqualTo(snapshotId)
		assertThat(second.at("/teams/nextCursor").isNull).isTrue()

		val wrongSort = get(org.tenant, "/analytics/teams?$week&limit=3&sort=token&snapshotId=$snapshotId&cursor=$cursor")
		assertThat(wrongSort.statusCode()).isEqualTo(400)
		assertThat(DashboardHttp.json(wrongSort).at("/error/fieldErrors/0/code").asString()).isEqualTo("invalid_cursor")
	}

	@Test
	@DisplayName("정렬 — token·session 도 지표 내림차순 + 팀 ID 오름차순이고 값이 같으면 ID 순이다")
	fun sortByTokenAndSession() {
		val org = seed()

		for (sort in listOf("token", "session")) {
			val body = ok(org.tenant, "/analytics/teams?$week&sort=$sort")
			val ids = body.at("/teams/items").list().map { it.path("teamId").asString() }
			assertThat(ids).describedAs(sort).isEqualTo(ids.sorted())
			assertThat(body.at("/sort").asString()).isEqualTo(sort)
		}
	}

	@Test
	@DisplayName("사례 16 — 목록 snapshot 뒤 원본이 교체돼도 처음 부르는 사용자·상세 endpoint 가 같은 값·순서·distinct 를 낸다")
	fun sameSnapshotAcrossEndpoints() {
		val org = seed()
		val list = ok(org.tenant, "/analytics/teams?$week")
		val snapshotId = list.at("/meta/snapshotId").asString()
		val teamA = org.teams.getValue("A")

		// 원본 교체 — A-1 의 비용을 바꾸고 A 에 새 사용자를 더한다.
		SourceFixtures.insertEvents(
			org.tenant,
			Event("${org.tenant}-A-1", kst("2026-09-08T10:00:00"), rowVersion = 2, teamId = teamA, memberId = org.members.getValue("A-1"),
				sessionId = "A-session-1", model = "model-x", costEstimatedUsd = BigDecimal("999"), pricingVersion = "v1"),
			Event("${org.tenant}-A-new", kst("2026-09-10T10:00:00"), teamId = teamA, memberId = UUID.randomUUID(), costEstimatedUsd = BigDecimal("1"), pricingVersion = "v1"),
		)

		val users = ok(org.tenant, "/analytics/teams/$teamA/users?$week&snapshotId=$snapshotId")
		assertThat(users.at("/meta/snapshotId").asString()).isEqualTo(snapshotId)
		assertThat(users.at("/users/items").list().map { it.path("memberId").asString() })
			.containsExactlyInAnyOrder(org.members.getValue("A-1").toString(), org.members.getValue("A-2").toString())
		assertThat(users.at("/users/totalCount").asInt()).isEqualTo(2)
		assertThat(money(users.at("/summary/usage/equivalentCostUsd"))).isEqualByComparingTo("40")
		assertThat(users.at("/summary/usage/activeUsers").asInt()).isEqualTo(2)

		val detail = ok(org.tenant, "/analytics/teams/$teamA?$week&snapshotId=$snapshotId")
		assertThat(money(detail.at("/team/current/equivalentCostUsd"))).isEqualByComparingTo(money(list.at("/teams/items/0/current/equivalentCostUsd")))

		// snapshot ID 없이 새로 부르면 새 원본을 본다.
		assertThat(money(ok(org.tenant, "/analytics/teams/$teamA/users?$week").at("/summary/usage/equivalentCostUsd"))).isEqualByComparingTo("1020")
	}

	@Test
	@DisplayName("사용자 — 비용 내림차순, 주요 모델·캐시 적중·팀 안 마지막 사용, 식별된 평균과 미식별 비용")
	fun teamUsers() {
		val tenant = DashboardTestStores.insertTenant()
		SourceFixtures.setSummary(tenant, firstReceivedAt = kst("2026-09-01T00:00:00"))
		val team = SourceFixtures.insertTeam(tenant, "플랫폼")
		val other = SourceFixtures.insertTeam(tenant, "결제")
		val alice = SourceFixtures.insertMember(tenant, "alice@example.test")
		val bob = SourceFixtures.insertMember(tenant, "bob@example.test")
		SourceFixtures.insertEvents(
			tenant,
			Event("$tenant-a1", kst("2026-09-08T10:00:00"), teamId = team, memberId = alice, model = "model-x", costEstimatedUsd = BigDecimal("3"), pricingVersion = "v1",
				tokensInputUncached = 60, tokensCacheRead = 30, tokensCacheCreate = 10),
			Event("$tenant-a2", kst("2026-09-09T11:00:00"), teamId = team, memberId = alice, model = "model-y", costEstimatedUsd = BigDecimal("1"), pricingVersion = "v1"),
			// 다른 팀에서의 사용은 이 팀의 마지막 사용이 아니다.
			Event("$tenant-a3", kst("2026-09-12T10:00:00"), teamId = other, memberId = alice, costEstimatedUsd = BigDecimal("1"), pricingVersion = "v1"),
			Event("$tenant-b1", kst("2026-09-10T10:00:00"), teamId = team, memberId = bob, costEstimatedUsd = BigDecimal("2"), pricingVersion = "v1"),
			Event("$tenant-anon", kst("2026-09-10T12:00:00"), teamId = team, memberId = null, costEstimatedUsd = BigDecimal("0.5"), pricingVersion = "v1"),
		)

		val body = ok(tenant, "/analytics/teams/$team/users?$week")

		val users = body.at("/users/items").list()
		assertThat(users.map { it.path("account").asString() }).containsExactly("alice@example.test", "bob@example.test")
		assertThat(users[0].at("/mainModel/modelId").asString()).isEqualTo("unknown/claude_code/model-x")
		assertThat(users[0].at("/usage/equivalentCostUsd").asString()).isEqualTo("4.000000")
		assertThat(Instant.parse(users[0].path("lastUsedAt").asString())).isEqualTo(kst("2026-09-09T11:00:00"))
		// alice: read 30+0, eligible (60+30+10)+(100+0+0) = 200.
		assertThat(users[0].at("/cache/readTokens").asLong()).isEqualTo(30)
		assertThat(users[0].at("/cache/eligibleInputTokens").asLong()).isEqualTo(200)
		assertThat(users[0].at("/cache/hitRatio").asDouble()).isEqualTo(0.15)
		assertThat(body.at("/summary/averageEquivalentCostUsd").asString()).isEqualTo("3.000000")
		assertThat(body.at("/summary/unidentifiedEquivalentCostUsd").asString()).isEqualTo("0.500000")
		assertThat(money(body.at("/summary/usage/equivalentCostUsd"))).isEqualByComparingTo("6.5")
		assertThat(body.at("/team/teamName").asString()).isEqualTo("플랫폼")

		val paged = ok(tenant, "/analytics/teams/$team/users?$week&limit=1")
		val next = ok(tenant, "/analytics/teams/$team/users?$week&limit=1&snapshotId=${paged.at("/meta/snapshotId").asString()}&cursor=${paged.at("/users/nextCursor").asString()}")
		assertThat((paged.at("/users/items").list() + next.at("/users/items").list()).map { it.path("account").asString() })
			.containsExactly("alice@example.test", "bob@example.test")
	}

	@Test
	@DisplayName("모델 산점도 — 모델 비용이 팀 비용의 5% 이상인 실제 팀 수, 미배정 사용은 모델 수치에 포함된다")
	fun modelScatter() {
		val tenant = DashboardTestStores.insertTenant()
		SourceFixtures.setSummary(tenant, firstReceivedAt = kst("2026-09-01T00:00:00"))
		val a = SourceFixtures.insertTeam(tenant, "A")
		val b = SourceFixtures.insertTeam(tenant, "B")
		fun e(name: String, team: UUID?, model: String, cost: String) =
			Event("$tenant-$name", kst("2026-09-08T10:00:00"), teamId = team, model = model, costEstimatedUsd = BigDecimal(cost), pricingVersion = "v1")
		SourceFixtures.insertEvents(
			tenant,
			e("a-x", a, "model-x", "4"), e("a-y", a, "model-y", "96"),
			e("b-x", b, "model-x", "5"), e("b-y", b, "model-y", "5"),
			e("u-x", null, "model-x", "1"),
		)

		val body = ok(tenant, "/analytics/teams?$week")

		val models = body.at("/modelScatter/data/models").list().associateBy { it.path("modelId").asString() }
		assertThat(body.at("/modelScatter/data/teamUsageCostShareThreshold").asDouble()).isEqualTo(0.05)
		// model-x: A 에서 4%(미만), B 에서 50% → 1팀. 조직 금액은 미배정 포함 10.
		assertThat(models.getValue("unknown/claude_code/model-x").path("usingTeamCount").asLong()).isEqualTo(1)
		assertThat(money(models.getValue("unknown/claude_code/model-x").path("equivalentCostUsd"))).isEqualByComparingTo("10")
		assertThat(models.getValue("unknown/claude_code/model-y").path("usingTeamCount").asLong()).isEqualTo(2)
	}

	@Test
	@DisplayName("상세 — 미배정 예약 경로는 teamId null, 모르는 팀·다른 조직의 팀·형식이 틀린 ID 는 404, 만료된 snapshot 은 409")
	fun detailPaths() {
		val org = seed()
		val other = DashboardTestStores.insertTenant()
		val foreignTeam = SourceFixtures.insertTeam(other, "남의 팀")

		val unassigned = ok(org.tenant, "/analytics/teams/unassigned?$week")
		assertThat(unassigned.at("/team/teamId").isNull).isTrue()
		assertThat(money(unassigned.at("/team/current/equivalentCostUsd"))).isEqualByComparingTo("5")

		val idle = ok(org.tenant, "/analytics/teams/${org.teams.getValue("E")}?$week")
		assertThat(idle.at("/team/current").isNull).isTrue()
		assertThat(idle.at("/team/modelMix/availability").asString()).isEqualTo("unavailable")

		for (path in listOf(UUID.randomUUID().toString(), foreignTeam.toString(), "not-a-team")) {
			assertThat(get(org.tenant, "/analytics/teams/$path?$week").statusCode()).describedAs(path).isEqualTo(404)
			assertThat(get(org.tenant, "/analytics/teams/$path/users?$week").statusCode()).describedAs(path).isEqualTo(404)
		}
		assertThat(get(org.tenant, "/analytics/teams/unassigned?$week&snapshotId=${"a".repeat(22)}").statusCode()).isEqualTo(409)
	}

	@Test
	@DisplayName("개인 목록 권한이 없으면 사용자 endpoint 는 403 이다")
	fun usersRequirePermission() {
		val org = seed()

		assertThat(get(org.tenant, "/analytics/teams/${org.teams.getValue("A")}/users?$week", Role.MEMBER).statusCode()).isEqualTo(403)
		assertThat(get(org.tenant, "/analytics/teams/unassigned/users?$week", Role.LEAD).statusCode()).isEqualTo(403)
	}

	@Test
	@DisplayName("응답의 키 구조가 요청서의 예시 JSON 과 같다 — 목록·사용자")
	fun structureMatchesExamples() {
		val org = seed()

		JsonStructure.assertMatches("teams-response.example.json", ok(org.tenant, "/analytics/teams?$week"))
		JsonStructure.assertMatches("team-users-response.example.json", ok(org.tenant, "/analytics/teams/${org.teams.getValue("A")}/users?$week"))
	}

	@Test
	@DisplayName("팀 선택지 — 활성 실제 팀만, q 검색, 같은 snapshot ID 의 페이지는 기준 시각까지의 팀, 남의·만료 ID 는 409")
	fun directory() {
		val tenant = DashboardTestStores.insertTenant()
		val names = listOf("플랫폼", "결제", "데이터", "플랫폼 인프라")
		names.forEach { SourceFixtures.insertTeam(tenant, it) }
		SourceFixtures.insertTeam(tenant, "옛 팀", archived = true)

		val all = ok(tenant, "/teams")
		assertThat(all.at("/teams/items").list().map { it.path("teamName").asString() }).containsExactlyInAnyOrderElementsOf(names)
		assertThat(all.at("/teams/totalCount").asInt()).isEqualTo(4)
		assertThat(all.at("/teams/items/0/version").asLong()).isPositive()
		assertThat(all.at("/meta/timeZone").asString()).isEqualTo("Asia/Seoul")

		val searched = ok(tenant, "/teams?q=${encode("플랫폼")}")
		assertThat(searched.at("/teams/items").list().map { it.path("teamName").asString() }).containsExactlyInAnyOrder("플랫폼", "플랫폼 인프라")
		assertThat(ok(tenant, "/teams?q=${encode("%")}").at("/teams/totalCount").asInt()).isZero()

		val first = ok(tenant, "/teams?limit=2")
		val snapshotId = first.at("/meta/snapshotId").asString()
		SourceFixtures.insertTeam(tenant, "나중 팀")
		val second = ok(tenant, "/teams?limit=2&snapshotId=$snapshotId&cursor=${first.at("/teams/nextCursor").asString()}")
		val paged = (first.at("/teams/items").list() + second.at("/teams/items").list()).map { it.path("teamName").asString() }
		assertThat(paged).containsExactlyInAnyOrderElementsOf(names)
		assertThat(second.at("/teams/nextCursor").isNull).isTrue()
		assertThat(second.at("/meta/asOf").asString()).isEqualTo(first.at("/meta/asOf").asString())

		val otherTenant = DashboardTestStores.insertTenant()
		assertThat(get(otherTenant, "/teams?snapshotId=$snapshotId").statusCode()).isEqualTo(409)
		assertThat(get(tenant, "/teams?snapshotId=d.broken").statusCode()).isEqualTo(409)
		assertThat(get(tenant, "/teams?q=${encode("결")}&snapshotId=$snapshotId&cursor=${first.at("/teams/nextCursor").asString()}").statusCode()).isEqualTo(400)
	}
}
