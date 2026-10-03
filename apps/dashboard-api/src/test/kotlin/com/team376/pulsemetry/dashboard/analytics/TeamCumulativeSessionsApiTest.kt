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
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

/**
 * 팀 누적 세션(대시보드 명세 "팀 누적 세션") — `trend[].cumulativeSessionCount`. 기대값은 명세의 규칙에서 쓴다:
 * 기간 시작일부터 그날까지 그 팀에서 관측된 고유 세션 수이고(세션 키는 `sessionCount` 와 같은 `(product, session_id_namespace, session_id)`),
 * 여러 날에 걸친 세션은 처음 본 날에 한 번만 더한다. 시작일부터 그날까지 모든 날이 완전 관측(ADR 0042)이고 세션 없는 사용 행이 없을 때만 값이다.
 *
 * 기간 2026-09-07~13(Asia/Seoul). 설치는 8월 1일에 등록해 9월 20일까지 손실 없이 수집했다고 보고한 것으로 둔다. 테스트 설정의 확정 대기는 1시간이다.
 */
class TeamCumulativeSessionsApiTest : AbstractDashboardApiTest() {

	@BeforeEach
	fun backfillDone() { SourceFixtures.completeBackfill() }

	private fun kst(dateTime: String): Instant = LocalDateTime.parse(dateTime).atZone(QueryReader.SEOUL).toInstant()

	private val week = "startDate=2026-09-07&endDate=2026-09-13"

	private data class Org(val tenant: UUID, val a: UUID, val b: UUID, val memberA: UUID, val memberB: UUID, val installation: UUID)

	/** 사용량을 싣는 정책이 7월부터 효력이 있고, 설치 하나가 8월 1일부터 9월 20일까지 손실 없이 덮인 조직의 두 팀. */
	private fun coveredOrganization(): Org {
		val tenant = DashboardTestStores.insertTenant()
		SourceFixtures.setSummary(tenant, firstReceivedAt = kst("2026-08-01T00:00:00"), lastReceivedAt = kst("2026-09-20T00:00:00"))
		val owner = SourceFixtures.insertMember(tenant, "owner-${UUID.randomUUID()}@example.test", role = "owner")
		SourceFixtures.insertManifest(tenant, 1, owner, signals = """{"logs":true,"metrics":true,"traces":true}""", activatedAt = kst("2026-07-01T00:00:00"))
		val a = SourceFixtures.insertTeam(tenant, "팀 A")
		val b = SourceFixtures.insertTeam(tenant, "팀 B")
		val memberA = SourceFixtures.insertMember(tenant, "a-${UUID.randomUUID()}@example.test")
		val memberB = SourceFixtures.insertMember(tenant, "b-${UUID.randomUUID()}@example.test")
		SourceFixtures.insertMembership(a, memberA, kst("2026-07-01T00:00:00"))
		SourceFixtures.insertMembership(b, memberB, kst("2026-07-01T00:00:00"))
		val installation = SourceFixtures.insertInstallation(tenant, memberA)
		SourceFixtures.setInstallationTimes(installation, kst("2026-08-01T00:00:00"))
		SourceFixtures.insertSegment(installation, kst("2026-08-01T00:00:00"), kst("2026-09-20T00:00:00"))
		return Org(tenant, a, b, memberA, memberB, installation)
	}

	private fun event(org: Org, name: String, at: String, team: UUID, session: String?, product: String = "claude_code") = Event(
		"${org.tenant}-$name", kst(at), product = product, memberId = if (team == org.a) org.memberA else org.memberB, teamId = team, sessionId = session,
		costEstimatedUsd = BigDecimal("1"), pricingVersion = "v1",
	)

	/**
	 * 팀 A: s1 은 9/8·9/10 두 날(처음 본 9/8 에 한 번), s2 는 9/8 하루, `shared` 는 9/9, s3 은 9/10, 같은 s1 이지만 Codex 의 세션은 9/12(제품이 달라 다른 세션).
	 * 팀 B: `shared` 의 나머지 이벤트가 9/11(팀마다 따로 센다), s4 는 서울 9/12 00:30(UTC 로는 9/11).
	 */
	private fun withSessions(org: Org): Org {
		SourceFixtures.insertEvents(
			org.tenant,
			event(org, "a-s1-day1", "2026-09-08T10:00:00", org.a, "s1"),
			event(org, "a-s1-day3", "2026-09-10T10:00:00", org.a, "s1"),
			event(org, "a-s2", "2026-09-08T15:00:00", org.a, "s2"),
			event(org, "a-shared", "2026-09-09T10:00:00", org.a, "shared"),
			event(org, "a-s3", "2026-09-10T11:00:00", org.a, "s3"),
			event(org, "a-codex-s1", "2026-09-12T10:00:00", org.a, "s1", product = "codex").copy(serviceName = "codex-app-server", semanticsProfile = "codex-inclusive-v1"),
			event(org, "b-shared", "2026-09-11T10:00:00", org.b, "shared"),
			event(org, "b-s4", "2026-09-12T00:30:00", org.b, "s4"),
		)
		return org
	}

	private fun ok(tenant: UUID, path: String): JsonNode {
		val response = http.send("/api/v1/organizations/$tenant$path",
			headers = mapOf("Authorization" to TestDashboardAuthenticator.header(DashboardPrincipal(tenant, UUID.randomUUID(), Role.ADMIN))))
		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200)
		return DashboardHttp.json(response)
	}

	/** 날짜 → 누적 세션(없으면 null). */
	private fun cumulative(team: JsonNode): Map<String, Long?> =
		team.path("trend").toList().associate { it.path("date").asString() to it.path("cumulativeSessionCount").let { v -> if (v.isNull) null else v.asLong() } }

	private fun teams(list: JsonNode): Map<String, JsonNode> = list.at("/teams/items").toList().associateBy { it.path("teamId").asString() }

	@Test
	@DisplayName("완전한 기간 — 세션은 처음 본 날에 한 번만 더하고, 두 팀으로 나뉜 세션은 팀마다 따로, 날짜는 조회 시간대로 가른다")
	fun completePeriod() {
		val org = withSessions(coveredOrganization())
		val list = ok(org.tenant, "/analytics/teams?$week")
		val byTeam = teams(list)

		assertThat(cumulative(byTeam.getValue(org.a.toString()))).containsExactlyEntriesOf(linkedMapOf(
			"2026-09-07" to 0L, "2026-09-08" to 2L, "2026-09-09" to 3L, "2026-09-10" to 4L,
			"2026-09-11" to 4L, "2026-09-12" to 5L, "2026-09-13" to 5L,
		))
		assertThat(cumulative(byTeam.getValue(org.b.toString()))).containsExactlyEntriesOf(linkedMapOf(
			"2026-09-07" to 0L, "2026-09-08" to 0L, "2026-09-09" to 0L, "2026-09-10" to 0L,
			"2026-09-11" to 1L, "2026-09-12" to 2L, "2026-09-13" to 2L,
		))
		// 사용이 없는 미배정도 완전한 날은 0 이다.
		assertThat(cumulative(list.path("unassigned")).values.toSet()).containsExactly(0L)

		// 마지막 날의 누적 = 그 기간의 팀 세션 수.
		for (team in byTeam.values) {
			assertThat(team.at("/trend").toList().last().path("cumulativeSessionCount").asLong()).isEqualTo(team.at("/current/sessionCount").asLong())
		}
		JsonStructure.assertMatches("teams-response.example.json", list)
	}

	@Test
	@DisplayName("팀 상세는 같은 snapshot 의 팀 목록과 같은 누적 세션을 낸다")
	fun detailMatchesList() {
		val org = withSessions(coveredOrganization())
		val list = ok(org.tenant, "/analytics/teams?$week")
		val snapshotId = list.at("/meta/snapshotId").asString()
		val byTeam = teams(list)
		for (id in listOf(org.a, org.b)) {
			val detail = ok(org.tenant, "/analytics/teams/$id?$week&snapshotId=$snapshotId")
			assertThat(detail.at("/team/trend")).isEqualTo(byTeam.getValue(id.toString()).path("trend"))
		}
		assertThat(ok(org.tenant, "/analytics/teams/unassigned?$week&snapshotId=$snapshotId").at("/team/trend")).isEqualTo(list.at("/unassigned/trend"))
	}

	@Test
	@DisplayName("중간에 완전하지 않은 날이 있으면 그날부터 끝까지 누적이 없다 — 뒤의 완전한 날도 이어 붙이지 않는다")
	fun gapBreaksTheRest() {
		val org = withSessions(coveredOrganization())
		// 9/10 12:00~12:10 사이에 데몬 프로세스가 바뀌었다.
		DashboardTestStores.writer.sql("DELETE FROM enrollment.installation_collection_segments WHERE installation_id = :id").param("id", org.installation).update()
		SourceFixtures.insertSegment(org.installation, kst("2026-08-01T00:00:00"), kst("2026-09-10T12:00:00"))
		SourceFixtures.insertSegment(org.installation, kst("2026-09-10T12:10:00"), kst("2026-09-20T00:00:00"))

		val teamA = teams(ok(org.tenant, "/analytics/teams?$week")).getValue(org.a.toString())
		assertThat(cumulative(teamA)).containsExactlyEntriesOf(linkedMapOf(
			"2026-09-07" to 0L, "2026-09-08" to 2L, "2026-09-09" to 3L, "2026-09-10" to null,
			"2026-09-11" to null, "2026-09-12" to null, "2026-09-13" to null,
		))
		// 9/11 은 그 자체로는 완전한 날이다.
		assertThat(teamA.at("/trend").toList().single { it.path("date").asString() == "2026-09-11" }.path("observation").asString()).isEqualTo("complete")
	}

	@Test
	@DisplayName("세션 없는 사용 행이 있는 날부터 그 팀의 누적이 없다 — 다른 팀은 그대로")
	fun sessionlessRow() {
		val org = withSessions(coveredOrganization())
		SourceFixtures.insertEvents(org.tenant, event(org, "b-sessionless", "2026-09-10T09:00:00", org.b, null))

		val byTeam = teams(ok(org.tenant, "/analytics/teams?$week"))
		val teamB = byTeam.getValue(org.b.toString())
		assertThat(cumulative(teamB)).containsExactlyEntriesOf(linkedMapOf(
			"2026-09-07" to 0L, "2026-09-08" to 0L, "2026-09-09" to 0L, "2026-09-10" to null,
			"2026-09-11" to null, "2026-09-12" to null, "2026-09-13" to null,
		))
		// 기간 세션 수도 같은 이유로 없다.
		assertThat(teamB.at("/current/sessionCount").isNull).isTrue()
		assertThat(cumulative(byTeam.getValue(org.a.toString()))["2026-09-13"]).isEqualTo(5L)
	}

	@Test
	@DisplayName("완전성 근거가 없는 조직은 첫날부터 누적이 없다 — 기간 세션 수를 나눠 선을 만들지 않는다")
	fun withoutEvidence() {
		val tenant = DashboardTestStores.insertTenant()
		SourceFixtures.setSummary(tenant, firstReceivedAt = kst("2026-09-01T00:00:00"))
		val team = SourceFixtures.insertTeam(tenant, "팀")
		val member = SourceFixtures.insertMember(tenant, "m-${UUID.randomUUID()}@example.test")
		SourceFixtures.insertEvents(tenant, Event("$tenant-e", kst("2026-09-08T10:00:00"), memberId = member, teamId = team, sessionId = "s",
			costEstimatedUsd = BigDecimal("1"), pricingVersion = "v1"))

		val item = ok(tenant, "/analytics/teams?$week").at("/teams/items").single()
		assertThat(item.at("/current/sessionCount").asLong()).isEqualTo(1)
		assertThat(cumulative(item).values.toSet()).containsExactly(null)
	}
}
