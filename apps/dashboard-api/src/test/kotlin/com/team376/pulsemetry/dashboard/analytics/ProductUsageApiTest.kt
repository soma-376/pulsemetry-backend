package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.authentication.DashboardPrincipal
import com.team376.pulsemetry.dashboard.authentication.Role
import com.team376.pulsemetry.dashboard.request.QueryReader
import com.team376.pulsemetry.dashboard.support.AbstractDashboardApiTest
import com.team376.pulsemetry.dashboard.support.DashboardHttp
import com.team376.pulsemetry.dashboard.support.DashboardTestStores
import com.team376.pulsemetry.dashboard.support.SourceFixtures
import com.team376.pulsemetry.dashboard.support.SourceFixtures.Event
import com.team376.pulsemetry.dashboard.support.TestDashboardAuthenticator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

/**
 * 카탈로그 제품별 사용 (ADR 0045) — 개요의 제품별 사용·팀별 관측 제품, 팀 목록·상세의 팀별 제품 집계. 기대값은 ADR 의 규칙에서 쓴다:
 * 관측 제품은 snapshot 에 복제한 명시 매핑(`claude_code` → `claude_team`, `codex` → `openai_biz`)으로만 잇고, 매핑 없는 관측은 `kind = null` 로 따로 둔다.
 * 값은 [UsageTotals] 의 null 규칙을 따른다.
 */
class ProductUsageApiTest : AbstractDashboardApiTest() {

	@BeforeEach
	fun backfillDone() {
		SourceFixtures.completeBackfill()
	}

	private val week = "startDate=2026-09-07&endDate=2026-09-13"

	private fun kst(dateTime: String): Instant = LocalDateTime.parse(dateTime).atZone(QueryReader.SEOUL).toInstant()

	private fun ok(tenant: UUID, path: String): JsonNode {
		val response = http.send("/api/v1/organizations/$tenant$path",
			headers = mapOf("Authorization" to TestDashboardAuthenticator.header(DashboardPrincipal(tenant, UUID.randomUUID(), Role.ADMIN))))
		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200)
		return DashboardHttp.json(response)
	}

	private fun JsonNode.list(): List<JsonNode> = (0 until size()).map { get(it) }
	private fun JsonNode.text(): String? = if (isNull) null else asString()
	private fun product(node: JsonNode) = listOf(node.path("kind").text(), node.path("displayName").text(), node.path("activeUsers").text(),
		node.path("sessionCount").text(), node.path("totalTokens").text(), node.path("equivalentCostUsd").text()?.let { BigDecimal(it).stripTrailingZeros().toPlainString() })

	private data class Org(val tenant: UUID, val a: UUID, val b: UUID)

	/**
	 * 팀 A: Claude Code(구성원 a1, 금액 1, 토큰 110) · Codex(a2, 금액 2, 토큰 110). 팀 B: Claude Code(b1, 금액 3) · 세션 없는 Codex(b2, 금액 4).
	 * 미배정: 매핑 없는 관측(product = unknown, 금액 5).
	 */
	private fun seed(): Org {
		val tenant = DashboardTestStores.insertTenant()
		SourceFixtures.setSummary(tenant, firstReceivedAt = kst("2026-09-01T00:00:00"))
		val a = SourceFixtures.insertTeam(tenant, "팀 A")
		val b = SourceFixtures.insertTeam(tenant, "팀 B")
		val (a1, a2, b1, b2, u1) = List(5) { SourceFixtures.insertMember(tenant, "m$it-${UUID.randomUUID()}@example.test") }
		fun codex(name: String, member: UUID, team: UUID?, cost: String, session: String?) = Event(name, kst("2026-09-09T10:00:00"), product = "codex",
			serviceName = "codex-app-server", semanticsProfile = "codex-inclusive-v1", memberId = member, teamId = team, sessionId = session,
			costEstimatedUsd = BigDecimal(cost), pricingVersion = "v1")
		SourceFixtures.insertEvents(
			tenant,
			Event("$tenant-a1", kst("2026-09-08T10:00:00"), memberId = a1, teamId = a, sessionId = "s-a1", costEstimatedUsd = BigDecimal("1"), pricingVersion = "v1"),
			codex("$tenant-a2", a2, a, "2", "s-a2"),
			Event("$tenant-b1", kst("2026-09-10T10:00:00"), memberId = b1, teamId = b, sessionId = "s-b1", costEstimatedUsd = BigDecimal("3"), pricingVersion = "v1"),
			codex("$tenant-b2", b2, b, "4", null),
			Event("$tenant-u1", kst("2026-09-11T10:00:00"), product = "unknown", serviceName = "some-tool", semanticsProfile = null, memberId = u1,
				sessionId = "s-u1", costEstimatedUsd = BigDecimal("5"), pricingVersion = "v1"),
		)
		return Org(tenant, a, b)
	}

	@Test
	@DisplayName("개요 — 등록 제품별 관측 인원·세션·토큰·금액(카탈로그 순서, 매핑 없는 관측은 끝에 kind = null), 제품 금액의 합 = 조직 금액")
	fun overviewProducts() {
		val org = seed()
		val body = ok(org.tenant, "/analytics/overview?$week")
		val section = body.path("productUsage")

		// Claude Code: a1·b1 두 명·세션 둘·같은 의미 프로파일이라 토큰 합 220·금액 4. Codex: 세션 없는 행이 있어 세션 수 없음·금액 6.
		assertThat(section.path("products").list().map(::product)).containsExactly(
			listOf("claude_team", "Claude (Anthropic)", "2", "2", "220", "4"),
			listOf("openai_biz", "ChatGPT / Codex (OpenAI)", "2", null, "220", "6"),
			listOf(null, null, "1", "1", null, "5"),
		)
		// 매핑 없는 관측은 의미 프로파일이 없어 토큰을 더하지 않는다 — 값 일부가 없으면 partial.
		assertThat(section.path("availability").asString()).isEqualTo("partial")
		val productCost = section.path("products").list().sumOf { BigDecimal(it.path("equivalentCostUsd").asString()) }
		assertThat(productCost).isEqualByComparingTo(BigDecimal(body.at("/usage/current/equivalentCostUsd").asString()))

		// 상위 팀별·미배정의 관측 제품.
		val teams = body.at("/teamUsage/topTeams").list().associate { it.path("teamId").asString() to it.path("products").list().map { p -> p.path("kind").text() } }
		assertThat(teams).isEqualTo(mapOf(org.b.toString() to listOf("claude_team", "openai_biz"), org.a.toString() to listOf("claude_team", "openai_biz")))
		assertThat(body.at("/teamUsage/unassigned/products").list().map { listOf(it.path("kind").text(), it.path("displayName").text()) })
			.containsExactly(listOf(null, null))
	}

	@Test
	@DisplayName("팀 목록·상세 — 팀별 제품 집계는 같은 snapshot 에서 같고, 금액이 모두 있으면 제품 합 = 팀 합계")
	fun teamProducts() {
		val org = seed()
		val list = ok(org.tenant, "/analytics/teams?$week")
		val snapshotId = list.at("/meta/snapshotId").asString()
		val teams = list.at("/teams/items").list().associateBy { it.path("teamId").asString() }

		val teamA = teams.getValue(org.a.toString())
		assertThat(teamA.path("products").list().map(::product)).containsExactly(
			listOf("claude_team", "Claude (Anthropic)", "1", "1", "110", "1"),
			listOf("openai_biz", "ChatGPT / Codex (OpenAI)", "1", "1", "110", "2"),
		)
		// 팀 합계의 토큰은 의미 프로파일이 섞여 없지만 제품별 토큰은 있다(같은 프로파일 안에서만 더한다).
		assertThat(teamA.at("/current/tokens/total").isNull).isTrue()
		for (team in teams.values) {
			val sum = team.path("products").list().sumOf { BigDecimal(it.path("equivalentCostUsd").asString()) }
			assertThat(sum).isEqualByComparingTo(BigDecimal(team.at("/current/equivalentCostUsd").asString()))
		}
		// 세션 없는 행이 있는 제품은 세션 수가 없다.
		assertThat(teams.getValue(org.b.toString()).path("products").list().map { it.path("sessionCount").text() }).containsExactly("1", null)
		assertThat(list.at("/unassigned/products").list().map(::product)).containsExactly(listOf(null, null, "1", "1", null, "5"))

		for (id in listOf(org.a, org.b)) {
			val detail = ok(org.tenant, "/analytics/teams/$id?$week&snapshotId=$snapshotId")
			assertThat(detail.at("/team/products")).isEqualTo(teams.getValue(id.toString()).path("products"))
		}
		assertThat(ok(org.tenant, "/analytics/teams/unassigned?$week&snapshotId=$snapshotId").at("/team/products")).isEqualTo(list.at("/unassigned/products"))
	}

	@Test
	@DisplayName("사용이 없으면 제품 집계는 unavailable·빈 목록이다 — 0 을 지어내지 않는다")
	fun noUsage() {
		val tenant = DashboardTestStores.insertTenant()
		SourceFixtures.setSummary(tenant, firstReceivedAt = kst("2026-09-01T00:00:00"))
		val body = ok(tenant, "/analytics/overview?$week")
		assertThat(listOf(body.at("/productUsage/availability").asString(), body.at("/productUsage/products").size())).containsExactly("unavailable", 0)
	}
}

private operator fun <T> List<T>.component5(): T = this[4]
