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
 * 구성원 화면 조회를 HTTP 끝까지 (구성원 명세). 기대값은 명세의 "숫자의 의미"와 수용 기준 문장에서 쓴다.
 */
class MembersApiTest : AbstractDashboardApiTest() {

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

	private data class Org(val tenant: UUID, val platform: UUID, val payments: UUID, val alice: UUID, val bob: UUID, val carol: UUID, val dave: UUID)

	/**
	 * alice(플랫폼, 기간 안 사용 + 기간 뒤 다른 팀 사용) · bob(무소속, 기간 안 사용) · carol(플랫폼, 사용 없음) · dave(두 팀 소속, 사용 없음) ·
	 * erin(초대 중 — 로스터 아님). 미식별 사용 하나.
	 */
	private fun seed(): Org {
		val tenant = DashboardTestStores.insertTenant()
		SourceFixtures.setSummary(tenant, firstReceivedAt = kst("2026-08-01T00:00:00"))
		val platform = SourceFixtures.insertTeam(tenant, "플랫폼")
		val payments = SourceFixtures.insertTeam(tenant, "결제")
		val alice = SourceFixtures.insertMember(tenant, "alice@example.test", role = "owner", displayName = "앨리스")
		val bob = SourceFixtures.insertMember(tenant, "bob@example.test")
		val carol = SourceFixtures.insertMember(tenant, "carol@example.test", status = "suspended")
		val dave = SourceFixtures.insertMember(tenant, "dave@example.test")
		SourceFixtures.insertMember(tenant, "erin@example.test", status = "invited")
		val joined = kst("2026-01-01T00:00:00")
		SourceFixtures.insertMembership(platform, alice, joined)
		SourceFixtures.insertMembership(platform, carol, joined)
		SourceFixtures.insertMembership(platform, dave, joined)
		SourceFixtures.insertMembership(payments, dave, joined)
		SourceFixtures.insertEvents(
			tenant,
			Event("$tenant-a-in", kst("2026-09-08T10:00:00"), memberId = alice, teamId = platform, costEstimatedUsd = BigDecimal("3"), pricingVersion = "v1"),
			// 기간 뒤, 다른 팀 — 구성원의 마지막 사용(asOf 까지·팀 무관)이지 팀 표의 기간 안 마지막 사용이 아니다.
			Event("$tenant-a-after", kst("2026-09-20T10:00:00"), memberId = alice, teamId = payments, costEstimatedUsd = BigDecimal("1"), pricingVersion = "v1"),
			Event("$tenant-b-in", kst("2026-09-09T10:00:00"), memberId = bob, costEstimatedUsd = BigDecimal("2"), pricingVersion = "v1"),
			Event("$tenant-b-before", kst("2026-08-15T10:00:00"), memberId = bob, costEstimatedUsd = BigDecimal("9"), pricingVersion = "v1"),
			Event("$tenant-anon", kst("2026-09-10T10:00:00"), memberId = null, costEstimatedUsd = BigDecimal("0.5"), pricingVersion = "v1"),
		)
		return Org(tenant, platform, payments, alice, bob, carol, dave)
	}

	@Test
	@DisplayName("dashboard — 로스터가 원천이다: 사용 없는 사람도 남고 초대 중인 사람은 로스터가 아니며, 비용 내림차순 + 사용 없는 사람은 마지막")
	fun dashboardRoster() {
		val org = seed()

		val body = ok(org.tenant, "/members/dashboard?$week")

		val members = body.at("/members/items").list()
		// 비용이 있는 alice(3)·bob(2) 다음에 사용 없는 carol·dave 가 memberId 오름차순으로 온다.
		val unused = listOf(org.carol, org.dave).sortedBy { it.toString() }.map { it.toString() }
		assertThat(members.map { it.path("memberId").asString() }).containsExactly(org.alice.toString(), org.bob.toString(), *unused.toTypedArray())
		assertThat(body.at("/summary/rosterMembers").asInt()).isEqualTo(4)
		assertThat(body.at("/summary/unassignedMembers").asInt()).isEqualTo(1)
		assertThat(body.at("/summary/activeUsers").asInt()).isEqualTo(2)
		// 기간 전체 비용(미식별 포함) = 3 + 2 + 0.5, 이벤트 당시 미배분 = bob 2 + 미식별 0.5.
		assertThat(BigDecimal(body.at("/summary/periodTotalEquivalentCostUsd").asString())).isEqualByComparingTo("5.5")
		assertThat(BigDecimal(body.at("/summary/periodUnassignedEquivalentCostUsd").asString())).isEqualByComparingTo("2.5")
		assertThat(body.at("/summary/seats/availability").asString()).isEqualTo("unavailable")
		assertThat(body.at("/summary/seats/reason").asString()).isEqualTo("not_applicable")
		assertThat(body.at("/policy/idleDays").asInt()).isEqualTo(14)
		assertThat(body.at("/policy/version").asLong()).isZero()
		assertThat(listOf("invite", "assignTeam", "reclaimSeats", "restoreSeats").map { body.at("/capabilities/$it").asBoolean() }).containsOnly(false)
		assertThat(body.at("/reclaimCandidates/availability").asString()).isEqualTo("unavailable")
		assertThat(body.at("/reclaimCandidates/data").isNull).isTrue()

		val alice = members.first { it.path("memberId").asString() == org.alice.toString() }
		assertThat(alice.path("role").asString()).isEqualTo("admin")
		assertThat(alice.path("displayName").asString()).isEqualTo("앨리스")
		assertThat(alice.at("/team/teamName").asString()).isEqualTo("플랫폼")
		assertThat(alice.at("/periodUsage/equivalentCostUsd").asString()).isEqualTo("3.000000")
		assertThat(alice.path("observation").asString()).isEqualTo("partial")
		assertThat(alice.path("seatState").asString()).isEqualTo("unknown")
		val carol = members.first { it.path("memberId").asString() == org.carol.toString() }
		assertThat(carol.path("periodUsage").isNull).isTrue()
		assertThat(carol.path("lastUsedAt").isNull).isTrue()
		assertThat(carol.path("status").asString()).isEqualTo("suspended")
		assertThat(carol.path("displayName").asString()).isEqualTo("carol@example.test")
	}

	@Test
	@DisplayName("세 lastUsedAt 은 범위가 다르다 — 구성원은 asOf 까지·팀 무관, 팀 표는 기간 안·그 팀")
	fun threeLastUsedAt() {
		val org = seed()

		val members = ok(org.tenant, "/members/dashboard?$week").at("/members/items").list().associateBy { it.path("memberId").asString() }
		assertThat(Instant.parse(members.getValue(org.alice.toString()).path("lastUsedAt").asString())).isEqualTo(kst("2026-09-20T10:00:00"))
		assertThat(Instant.parse(members.getValue(org.bob.toString()).path("lastUsedAt").asString())).isEqualTo(kst("2026-09-09T10:00:00"))

		val teamUsers = ok(org.tenant, "/analytics/teams/${org.platform}/users?$week").at("/users/items").list()
		assertThat(Instant.parse(teamUsers.single().path("lastUsedAt").asString())).isEqualTo(kst("2026-09-08T10:00:00"))
		// 기간 뒤의 결제 팀 사용은 이 기간의 결제 팀 표에 없다.
		assertThat(ok(org.tenant, "/analytics/teams/${org.payments}/users?$week").at("/users/items").size()).isZero()
	}

	@Test
	@DisplayName("미배정 목록은 현재 팀 없는 사람이다 — 두 팀에 속한 사람은 하나를 고르지 않고 두 이름을 적는다")
	fun unassignedAndMultiTeam() {
		val org = seed()

		val unassigned = ok(org.tenant, "/members/unassigned?$week")
		assertThat(unassigned.at("/members/items").list().map { it.path("memberId").asString() }).containsExactly(org.bob.toString())
		assertThat(unassigned.at("/members/items/0/team/teamId").isNull).isTrue()
		assertThat(unassigned.at("/members/items/0/team/teamName").asString()).isEqualTo("미배정")

		val dave = ok(org.tenant, "/members?$week&q=dave").at("/members/items/0")
		assertThat(dave.at("/team/teamId").isNull).isTrue()
		assertThat(dave.at("/team/teamName").asString()).isEqualTo("결제, 플랫폼")
	}

	@Test
	@DisplayName("검색은 로스터 전체에 적용하고(계정·이름, 대소문자 무시) 페이지는 같은 snapshot 에서 중복·누락이 없다")
	fun searchAndPaging() {
		val org = seed()

		val found = ok(org.tenant, "/members?$week&q=${URLEncoder.encode("앨리", StandardCharsets.UTF_8)}")
		assertThat(found.at("/members/items").list().map { it.path("memberId").asString() }).containsExactly(org.alice.toString())
		assertThat(ok(org.tenant, "/members?$week&q=BOB").at("/members/totalCount").asInt()).isEqualTo(1)

		val first = ok(org.tenant, "/members?$week&limit=3")
		val snapshotId = first.at("/meta/snapshotId").asString()
		val second = ok(org.tenant, "/members?$week&limit=3&snapshotId=$snapshotId&cursor=${first.at("/members/nextCursor").asString()}")
		val ids = (first.at("/members/items").list() + second.at("/members/items").list()).map { it.path("memberId").asString() }
		assertThat(ids).doesNotHaveDuplicates().containsExactlyInAnyOrder(org.alice.toString(), org.bob.toString(), org.carol.toString(), org.dave.toString())
		assertThat(second.at("/members/nextCursor").isNull).isTrue()

		// 다른 검색어의 cursor 는 이 목록의 것이 아니다.
		assertThat(get(org.tenant, "/members?$week&limit=3&q=a&snapshotId=$snapshotId&cursor=${first.at("/members/nextCursor").asString()}").statusCode()).isEqualTo(400)
	}

	@Test
	@DisplayName("수집 이력이 없어도 관리 목록은 동작한다 — 사용량 값만 null")
	fun rosterWithoutTelemetry() {
		val tenant = DashboardTestStores.insertTenant()
		SourceFixtures.insertMember(tenant, "only@example.test")

		val body = ok(tenant, "/members/dashboard?$week")

		assertThat(body.at("/meta/dataState").asString()).isEqualTo("never_observed")
		assertThat(body.at("/members/items").size()).isEqualTo(1)
		assertThat(body.at("/members/items/0/periodUsage").isNull).isTrue()
		assertThat(body.at("/members/items/0/observation").asString()).isEqualTo("unobserved")
		assertThat(body.at("/summary/activeUsers").isNull).isTrue()
		assertThat(body.at("/summary/periodTotalEquivalentCostUsd").isNull).isTrue()
	}

	@Test
	@DisplayName("회수 후보 — 좌석 원천이 없어 unavailable + not_applicable, snapshot ID 는 현재 상태 토큰이고 남의 것은 409, cursor 는 400")
	fun reclaimCandidates() {
		val org = seed()

		val body = ok(org.tenant, "/seat-reclaim-candidates")
		assertThat(body.at("/candidates/availability").asString()).isEqualTo("unavailable")
		assertThat(body.at("/candidates/reason").asString()).isEqualTo("not_applicable")
		assertThat(body.at("/candidates/data").isNull).isTrue()
		assertThat(body.at("/idleDays").asInt()).isEqualTo(14)
		val snapshotId = body.at("/meta/snapshotId").asString()
		assertThat(ok(org.tenant, "/seat-reclaim-candidates?snapshotId=$snapshotId").at("/meta/asOf").asString()).isEqualTo(body.at("/meta/asOf").asString())

		assertThat(get(DashboardTestStores.insertTenant(), "/seat-reclaim-candidates?snapshotId=$snapshotId").statusCode()).isEqualTo(409)
		val cursor = com.team376.pulsemetry.dashboard.request.PageCursorCodec(tools.jackson.databind.json.JsonMapper.builder().build())
			.encode(com.team376.pulsemetry.dashboard.request.PageCursor(snapshotId, "x", listOf("y")))
		assertThat(get(org.tenant, "/seat-reclaim-candidates?snapshotId=$snapshotId&cursor=$cursor").statusCode()).isEqualTo(400)
	}

	@Test
	@DisplayName("개인 목록 권한이 없으면 네 조회 모두 403 이다")
	fun requiresPermission() {
		val org = seed()

		for (path in listOf("/members/dashboard?$week", "/members?$week", "/members/unassigned?$week", "/seat-reclaim-candidates")) {
			assertThat(get(org.tenant, path, Role.MEMBER).statusCode()).describedAs(path).isEqualTo(403)
		}
	}

	@Test
	@DisplayName("응답의 키 구조가 요청서의 예시 JSON 과 같다")
	fun structureMatchesExample() {
		val org = seed()

		JsonStructure.assertMatches("members-response.example.json", ok(org.tenant, "/members/dashboard?$week"))
	}
}
