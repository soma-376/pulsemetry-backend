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
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

/**
 * `GET /analytics/overview` 를 HTTP 끝까지 (개요 명세 · 수용 사례). 기대값은 개요 명세 3·4·6절과 사례 문장에서 쓴다.
 */
class OverviewApiTest : AbstractDashboardApiTest() {

	@BeforeEach
	fun backfillDone() {
		SourceFixtures.completeBackfill()
	}

	private fun kst(dateTime: String): Instant = LocalDateTime.parse(dateTime).atZone(QueryReader.SEOUL).toInstant()

	private fun overview(tenant: UUID, query: String): JsonNode {
		val response = http.send(
			"/api/v1/organizations/$tenant/analytics/overview?$query",
			headers = mapOf("Authorization" to TestDashboardAuthenticator.header(DashboardPrincipal(tenant, UUID.randomUUID(), Role.ADMIN))),
		)
		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200)
		return DashboardHttp.json(response)
	}

	private fun JsonNode.list(): List<JsonNode> = (0 until size()).map { get(it) }

	private fun JsonNode.text(): String? = if (isNull) null else asString()

	@Test
	@DisplayName("사례 13 — 수신 이력도 관측도 없으면 never_observed, 사용량·모델·상위 팀은 비고 알려진 팀 수는 남는다")
	fun neverObserved() {
		val tenant = DashboardTestStores.insertTenant()
		SourceFixtures.insertTeam(tenant, "플랫폼")
		SourceFixtures.insertTeam(tenant, "결제")
		SourceFixtures.insertTeam(tenant, "옛 팀", archived = true)

		val body = overview(tenant, "startDate=2026-09-07&endDate=2026-09-13")

		assertThat(body.at("/meta/dataState").asString()).isEqualTo("never_observed")
		assertThat(body.at("/meta/currentCoverage/status").asString()).isEqualTo("none")
		assertThat(body.at("/meta/currentCoverage/observedDays").asInt()).isZero()
		assertThat(body.at("/usage/current").isNull).isTrue()
		assertThat(body.at("/modelMix/models").size()).isZero()
		assertThat(body.at("/teamUsage/topTeams").size()).isZero()
		assertThat(body.at("/teamUsage/totalTeamCount").asInt()).isEqualTo(2)
		assertThat(body.at("/teamUsage/unassigned/current/equivalentCostUsd").isNull).isTrue()
		assertThat(body.at("/ingest/status").asString()).isEqualTo("empty")
		assertThat(body.at("/trend/points").list().map { it.path("observation").asString() }).containsOnly("unobserved").hasSize(7)
		assertThat(body.at("/trend/points").list().map { it.path("equivalentCostUsd").isNull }).containsOnly(true)
	}

	@Test
	@DisplayName("사례 14 — 수신 이력은 있으나 요청 범위에 관측이 없으면 no_data")
	fun noData() {
		val tenant = DashboardTestStores.insertTenant()
		SourceFixtures.setSummary(tenant, firstReceivedAt = kst("2026-08-01T10:00:00"), lastReceivedAt = kst("2026-08-02T10:00:00"))

		val body = overview(tenant, "startDate=2026-09-07&endDate=2026-09-13")

		assertThat(body.at("/meta/dataState").asString()).isEqualTo("no_data")
		assertThat(body.at("/usage/current").isNull).isTrue()
		assertThat(body.at("/ingest/status").asString()).isEqualTo("unknown")
		assertThat(Instant.parse(body.at("/ingest/lastReceivedAt").asString())).isEqualTo(kst("2026-08-02T10:00:00"))
	}

	@Test
	@DisplayName("사례 25 — 원본 시각을 얻지 못한 push 만 받은 조직은 수신 이력이 있어 never_observed 가 아니고 firstObservedAt 은 null")
	fun receiptsWithoutSourceTime() {
		val tenant = DashboardTestStores.insertTenant()
		SourceFixtures.setSummary(tenant, firstReceivedAt = kst("2026-09-01T10:00:00"), lastReceivedAt = kst("2026-09-10T10:00:00"))

		val body = overview(tenant, "startDate=2026-09-07&endDate=2026-09-13")

		assertThat(body.at("/meta/dataState").asString()).isEqualTo("no_data")
		assertThat(body.at("/ingest/firstObservedAt").isNull).isTrue()
		assertThat(body.at("/ingest/lastReceivedAt").isNull).isFalse()
	}

	@Test
	@DisplayName("사례 24 — ledger 가 다 만료돼도 요약과 과거 분석 사용량이 있으면 partial 이고 수신 시각이 유지된다")
	fun ledgerExpiredButSummaryRemains() {
		val tenant = DashboardTestStores.insertTenant()
		SourceFixtures.setSummary(
			tenant,
			firstReceivedAt = kst("2026-01-01T09:00:00"),
			firstObservedAt = kst("2026-01-01T08:00:00"),
			lastReceivedAt = kst("2026-03-01T09:00:00"),
		)
		SourceFixtures.insertEvents(tenant, Event("old", kst("2026-02-10T10:00:00")))

		val body = overview(tenant, "startDate=2026-02-10&endDate=2026-02-10")

		assertThat(body.at("/meta/dataState").asString()).isEqualTo("partial")
		assertThat(Instant.parse(body.at("/ingest/firstObservedAt").asString())).isEqualTo(kst("2026-01-01T08:00:00"))
		assertThat(body.at("/ingest/observedMembers").asLong()).isZero()
		assertThat(body.at("/trend/points/0/observation").asString()).isEqualTo("partial")
	}

	@Test
	@DisplayName("사례 4·11·12·31 — 관측 일자는 source_time 날짜이고 complete 는 없다. 사이 날짜를 채우지 않고 메트릭만 있는 날은 값 없이 partial")
	fun trendObservation() {
		val tenant = DashboardTestStores.insertTenant()
		SourceFixtures.setSummary(tenant, firstReceivedAt = kst("2026-09-01T00:00:00"), lastReceivedAt = kst("2026-09-27T00:00:00"))
		SourceFixtures.insertEvents(
			tenant,
			Event("late", kst("2026-09-24T23:59:00"), receivedTime = kst("2026-09-25T00:01:00"), costEstimatedUsd = BigDecimal("2"), pricingVersion = "v1"),
			Event("d26", kst("2026-09-26T10:00:00"), costEstimatedUsd = BigDecimal("3"), pricingVersion = "v1"),
		)
		SourceFixtures.insertMetricPoint(tenant, "$tenant-metric", kst("2026-09-27T10:00:00"))

		val body = overview(tenant, "startDate=2026-09-24&endDate=2026-09-28")

		val points = body.at("/trend/points").list().map { listOf(it.path("date").asString(), it.path("observation").asString(), it.path("equivalentCostUsd").text(), it.path("totalTokens").text()) }
		assertThat(points).containsExactly(
			listOf("2026-09-24", "partial", "2.000000", "110"),
			listOf("2026-09-25", "unobserved", null, null),
			listOf("2026-09-26", "partial", "3.000000", "110"),
			listOf("2026-09-27", "partial", null, null),
			listOf("2026-09-28", "unobserved", null, null),
		)
		assertThat(body.at("/meta/dataState").asString()).isEqualTo("partial")
		assertThat(body.at("/meta/currentCoverage/status").asString()).isEqualTo("partial")
		assertThat(body.at("/meta/currentCoverage/observedDays").asInt()).isEqualTo(3)
		assertThat(body.at("/meta/dataThrough").isNull).isTrue()
		assertThat(body.at("/trend/points").list().map { it.path("allocatedSeatCostUsd").isNull }).containsOnly(true)
	}

	@Test
	@DisplayName("사례 28 — compare=none 이면 disabled 이고 비교 날짜·범위·이전 값이 모두 null, 비교를 요청해도 v1 은 unavailable")
	fun comparison() {
		val tenant = DashboardTestStores.insertTenant()
		SourceFixtures.setSummary(tenant, firstReceivedAt = kst("2026-09-01T00:00:00"))
		SourceFixtures.insertEvents(tenant, Event("now", kst("2026-09-08T10:00:00")), Event("before", kst("2026-09-01T10:00:00")))

		val none = overview(tenant, "startDate=2026-09-07&endDate=2026-09-13&compare=none")
		assertThat(none.at("/comparison/status").asString()).isEqualTo("disabled")
		assertThat(none.at("/comparison/mode").asString()).isEqualTo("none")
		assertThat(listOf("/comparison/startDate", "/comparison/endDate", "/comparison/coverage", "/comparison/reason", "/usage/previous").map { none.at(it).isNull })
			.containsOnly(true)

		val week = overview(tenant, "startDate=2026-09-07&endDate=2026-09-13")
		assertThat(week.at("/comparison/mode").asString()).isEqualTo("prev_week")
		assertThat(week.at("/comparison/status").asString()).isEqualTo("unavailable")
		assertThat(week.at("/comparison/reason").asString()).isEqualTo("source_not_available")
		assertThat(week.at("/comparison/startDate").asString()).isEqualTo("2026-08-31")
		assertThat(week.at("/comparison/coverage/status").asString()).isEqualTo("partial")
		assertThat(week.at("/usage/previous").isNull).isTrue()
		assertThat(week.at("/teamUsage/unassigned/previous").isNull).isTrue()
		assertThat(week.at("/teamUsage/otherTeams/previousEquivalentCostUsd").isNull).isTrue()
	}

	@Test
	@DisplayName("합계의 일관성 — 금액이 모두 있으면 조직 = 일자 합 = 모델 합 = 상위 3팀 + 나머지 + 미배분, 상위 3팀은 금액 내림차순")
	fun totalsAgree() {
		val tenant = DashboardTestStores.insertTenant()
		SourceFixtures.setSummary(tenant, firstReceivedAt = kst("2026-09-01T00:00:00"))
		val teams = listOf("A", "B", "C", "D", "E").associateWith { SourceFixtures.insertTeam(tenant, "팀 $it") }
		val costs = mapOf("A" to "5", "B" to "40", "C" to "12.5", "D" to "7", "E" to "1.25")
		var n = 0
		val events = costs.flatMap { (team, cost) ->
			listOf("claude-a", "claude-b").mapIndexed { index, model ->
				Event(
					"$tenant-${n++}", kst("2026-09-0${7 + index}T10:00:00"), teamId = teams.getValue(team), memberId = UUID.randomUUID(),
					model = model, costEstimatedUsd = BigDecimal(cost), pricingVersion = "rate-v1",
				)
			}
		} + Event("$tenant-unassigned", kst("2026-09-09T10:00:00"), costEstimatedUsd = BigDecimal("0.5"), pricingVersion = "rate-v1")
		SourceFixtures.insertEvents(tenant, *events.toTypedArray())

		val body = overview(tenant, "startDate=2026-09-07&endDate=2026-09-13")

		fun money(node: JsonNode) = BigDecimal(node.asString())
		val organization = money(body.at("/usage/current/equivalentCostUsd"))
		val byDay = body.at("/trend/points").list().mapNotNull { it.path("equivalentCostUsd").text() }.map(::BigDecimal).fold(BigDecimal.ZERO, BigDecimal::add)
		val byModel = body.at("/modelMix/models").list().map { money(it.path("equivalentCostUsd")) }.fold(BigDecimal.ZERO, BigDecimal::add)
		val top = body.at("/teamUsage/topTeams").list()
		val byTeam = top.map { money(it.at("/current/equivalentCostUsd")) }.fold(BigDecimal.ZERO, BigDecimal::add) +
			money(body.at("/teamUsage/otherTeams/currentEquivalentCostUsd")) + money(body.at("/teamUsage/unassigned/current/equivalentCostUsd"))

		// 팀마다 두 행: 10 + 80 + 25 + 14 + 2.5, 미배분 0.5.
		assertThat(organization).isEqualByComparingTo("132")
		assertThat(byDay).isEqualByComparingTo(organization)
		assertThat(byModel).isEqualByComparingTo(organization)
		assertThat(byTeam).isEqualByComparingTo(organization)
		assertThat(top.map { it.path("teamName").asString() }).containsExactly("팀 B", "팀 C", "팀 D")
		assertThat(body.at("/teamUsage/totalTeamCount").asInt()).isEqualTo(5)
		assertThat(body.at("/teamUsage/otherTeams/count").asInt()).isEqualTo(2)
		assertThat(body.at("/teamUsage/availability").asString()).isEqualTo("available")
		assertThat(body.at("/teamUsage/attributionBasis").asString()).isEqualTo("event_time")
		assertThat(body.at("/meta/pricingVersion").asString()).isEqualTo("rate-v1")
		assertThat(body.at("/usage/current/activeUsers").asInt()).isEqualTo(10)
		assertThat(body.at("/usage/current/tokens/total").asLong()).isEqualTo(11 * 110)
		// 팀 B 의 두 모델은 같은 금액 — 동률은 모델 ID 오름차순, 비율은 절반.
		assertThat(top[0].at("/topModel/modelId").asString()).isEqualTo("unknown/claude_code/claude-a")
		assertThat(top[0].at("/topModel/displayName").asString()).isEqualTo("claude-a")
		assertThat(top[0].at("/topModel/share").asDouble()).isEqualTo(0.5)
		val model = body.at("/modelMix/models/0")
		assertThat(model.path("effectiveCostPerMillionTokensUsd").asString())
			.isEqualTo(Money.format(Money.perMillion(money(model.path("equivalentCostUsd")), model.path("totalTokens").asLong())!!))
		assertThat(body.at("/modelMix/availability").asString()).isEqualTo("available")
	}

	@Test
	@DisplayName("사례 5 — 단가 없는 행이 하나 섞이면 조직·모델·팀·일자 금액은 null 이고 토큰·사용자는 유지된다")
	fun unpricedRowThroughHttp() {
		val tenant = DashboardTestStores.insertTenant()
		SourceFixtures.setSummary(tenant, firstReceivedAt = kst("2026-09-01T00:00:00"))
		val team = SourceFixtures.insertTeam(tenant, "플랫폼")
		val member = UUID.randomUUID()
		SourceFixtures.insertEvents(
			tenant,
			Event("$tenant-p", kst("2026-09-08T10:00:00"), teamId = team, memberId = member, costEstimatedUsd = BigDecimal("1"), pricingVersion = "v1"),
			Event("$tenant-u", kst("2026-09-08T11:00:00"), teamId = team, memberId = member),
		)

		val body = overview(tenant, "startDate=2026-09-07&endDate=2026-09-13")

		assertThat(body.at("/usage/current/equivalentCostUsd").isNull).isTrue()
		assertThat(body.at("/usage/current/tokens/output").asLong()).isEqualTo(20)
		assertThat(body.at("/usage/current/activeUsers").asInt()).isEqualTo(1)
		assertThat(body.at("/modelMix/models/0/equivalentCostUsd").isNull).isTrue()
		assertThat(body.at("/modelMix/availability").asString()).isEqualTo("partial")
		assertThat(body.at("/teamUsage/topTeams/0/current/equivalentCostUsd").isNull).isTrue()
		assertThat(body.at("/teamUsage/topTeams/0/topModel").isNull).isTrue()
		assertThat(body.at("/trend/points/1/equivalentCostUsd").isNull).isTrue()
		assertThat(body.at("/trend/points/1/totalTokens").asLong()).isEqualTo(220)
	}

	@Test
	@DisplayName("사례 7 — 가격 판이 섞이면 pricingVersion 과 모든 공시 환산 금액이 null 이다")
	fun mixedPricingThroughHttp() {
		val tenant = DashboardTestStores.insertTenant()
		SourceFixtures.setSummary(tenant, firstReceivedAt = kst("2026-09-01T00:00:00"))
		SourceFixtures.insertEvents(
			tenant,
			Event("$tenant-3", kst("2026-09-08T10:00:00"), costEstimatedUsd = BigDecimal("1"), pricingVersion = "v3"),
			Event("$tenant-4", kst("2026-09-08T11:00:00"), costEstimatedUsd = BigDecimal("1"), pricingVersion = "v4"),
		)

		val body = overview(tenant, "startDate=2026-09-07&endDate=2026-09-13")

		assertThat(body.at("/meta/pricingVersion").isNull).isTrue()
		assertThat(body.at("/usage/current/equivalentCostUsd").isNull).isTrue()
		assertThat(body.at("/teamUsage/unassigned/current/equivalentCostUsd").isNull).isTrue()
		assertThat(body.at("/usage/current/tokens/total").asLong()).isEqualTo(220)
	}

	@Test
	@DisplayName("수집 현황 — 최근 15분 ledger 의 설치를 구성원으로 풀어 세고, 대상 구성원은 active 인 사람 수다")
	fun ingestMembers() {
		val tenant = DashboardTestStores.insertTenant()
		SourceFixtures.setSummary(tenant, firstReceivedAt = kst("2026-09-01T00:00:00"))
		val alice = SourceFixtures.insertMember(tenant, "alice@example.test")
		val bob = SourceFixtures.insertMember(tenant, "bob@example.test")
		SourceFixtures.insertMember(tenant, "carol@example.test", status = "suspended")
		val aliceLaptop = SourceFixtures.insertInstallation(tenant, alice)
		val aliceDesktop = SourceFixtures.insertInstallation(tenant, alice)
		val bobLaptop = SourceFixtures.insertInstallation(tenant, bob)
		SourceFixtures.insertLedger(tenant, aliceLaptop, Instant.now().minusSeconds(60))
		SourceFixtures.insertLedger(tenant, aliceDesktop, Instant.now().minusSeconds(120))
		SourceFixtures.insertLedger(tenant, bobLaptop, Instant.now().minusSeconds(3600))

		val body = overview(tenant, "startDate=2026-09-07&endDate=2026-09-13")

		assertThat(body.at("/ingest/observedMembers").asLong()).isEqualTo(1)
		assertThat(body.at("/ingest/eligibleMembers").asLong()).isEqualTo(2)
		assertThat(body.at("/ingest/windowMinutes").asInt()).isEqualTo(15)
		assertThat(listOf("/ingest/activeInstallations", "/ingest/coverageRatio").map { body.at(it).isNull }).containsOnly(true)
	}

	@Test
	@DisplayName("D-9 — 요약도 백필 완료 기록도 없으면 수집한 적 없음으로 판정하지 않고 503")
	fun unknownHistoryIs503() {
		val tenant = DashboardTestStores.insertTenant()
		SourceFixtures.removeBackfill()
		try {
			val response = http.send(
				"/api/v1/organizations/$tenant/analytics/overview?startDate=2026-09-07&endDate=2026-09-13",
				headers = mapOf("Authorization" to TestDashboardAuthenticator.header(DashboardPrincipal(tenant, UUID.randomUUID(), Role.ADMIN))),
			)
			assertThat(response.statusCode()).isEqualTo(503)
			assertThat(response.headers().firstValue("Retry-After")).isPresent
		} finally {
			SourceFixtures.completeBackfill()
		}
	}

	@Test
	@DisplayName("고정 section — 좌석·알림·낭비는 unavailable 과 사유, 숫자는 모두 null")
	fun unsupportedSections() {
		val tenant = DashboardTestStores.insertTenant()
		val body = overview(tenant, "startDate=2026-09-07&endDate=2026-09-13")

		assertThat(body.at("/seats/availability").asString()).isEqualTo("unavailable")
		assertThat(body.at("/seats/reason").asString()).isEqualTo("not_applicable")
		assertThat(listOf("/seats/current", "/seats/previous", "/seats/reclaimEstimate", "/seats/allocationMethod").map { body.at(it).isNull }).containsOnly(true)
		assertThat(body.at("/alerts/reason").asString()).isEqualTo("evaluation_not_configured")
		assertThat(listOf("/alerts/unacknowledgedTotal", "/alerts/security", "/alerts/cost").map { body.at(it).isNull }).containsOnly(true)
		assertThat(body.at("/waste/items").list().map { it.path("kind").asString() to it.path("reason").asString() }).containsExactly(
			"cache_miss" to "methodology_not_available",
			"retry_or_abort" to "methodology_not_available",
			"excessive_context" to "methodology_not_available",
		)
		assertThat(body.at("/waste/totalMonthlyEquivalentCostUsd").isNull).isTrue()
	}

	@Test
	@DisplayName("응답의 키 구조가 요청서의 예시 JSON 과 같다 — 값이 아니라 키")
	fun structureMatchesExample() {
		val tenant = DashboardTestStores.insertTenant()
		SourceFixtures.setSummary(tenant, firstReceivedAt = kst("2026-09-01T00:00:00"))
		val team = SourceFixtures.insertTeam(tenant, "플랫폼")
		SourceFixtures.insertEvents(
			tenant,
			Event("$tenant-a", kst("2026-09-08T10:00:00"), teamId = team, memberId = UUID.randomUUID(), costEstimatedUsd = BigDecimal("1"), pricingVersion = "v1"),
		)
		val body = overview(tenant, "startDate=2026-09-07&endDate=2026-09-13")
		val example = JsonMapper.builder().build().readTree(Files.readString(EXAMPLE))

		assertSameKeys("", example, body)
	}

	/** 두 쪽이 모두 객체면 키 집합이 같아야 하고 재귀한다. 배열은 첫 원소끼리. 한쪽이 null 이면(nullable 필드) 거기서 멈춘다. */
	private fun assertSameKeys(path: String, expected: JsonNode, actual: JsonNode) {
		if (expected.isNull || actual.isNull) return
		if (expected.isObject) {
			assertThat(actual.isObject).describedAs(path).isTrue()
			assertThat(actual.propertyNames().asSequence().toSet()).describedAs(path).isEqualTo(expected.propertyNames().asSequence().toSet())
			for (name in expected.propertyNames()) assertSameKeys("$path/$name", expected.get(name), actual.get(name))
		} else if (expected.isArray && expected.size() > 0 && actual.size() > 0) {
			assertSameKeys("$path/0", expected.get(0), actual.get(0))
		}
	}

	private companion object {
		/** 요청서의 예시 JSON 사본 — 테스트 리소스. */
		val EXAMPLE: Path = Path.of("src/test/resources/frontend-api/overview-response.example.json")
	}
}
