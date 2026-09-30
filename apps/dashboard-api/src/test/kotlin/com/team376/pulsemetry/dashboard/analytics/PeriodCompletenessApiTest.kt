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
import java.net.http.HttpResponse
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

/**
 * 기간 완전성과 이전 기간 비교 (ADR 0042). 비교는 **두 기간 모두 완전 관측일 때만** 나온다. 테스트 설정의 확정 대기는 1시간이다.
 *
 * 현재 기간 2026-09-07~13, 비교(prev_week) 2026-08-31~09-06. 설치는 8월 1일에 등록해 9월 20일까지 손실 없이 수집했다고 보고한 것으로 둔다.
 */
class PeriodCompletenessApiTest : AbstractDashboardApiTest() {

	@BeforeEach
	fun backfillDone() { SourceFixtures.completeBackfill() }

	private fun kst(dateTime: String): Instant = LocalDateTime.parse(dateTime).atZone(QueryReader.SEOUL).toInstant()

	private val week = "startDate=2026-09-07&endDate=2026-09-13&compare=prev_week"

	private data class Org(val tenant: UUID, val team: UUID, val member: UUID, val installation: UUID)

	/** 사용량을 싣는 정책(logs)이 8월 전부터 효력이 있고, 설치 하나가 8월 1일부터 9월 20일까지 손실 없이 덮인 조직. */
	private fun coveredOrganization(): Org {
		val tenant = DashboardTestStores.insertTenant()
		SourceFixtures.setSummary(tenant, firstReceivedAt = kst("2026-08-01T00:00:00"), lastReceivedAt = kst("2026-09-20T00:00:00"))
		val owner = SourceFixtures.insertMember(tenant, "owner-${UUID.randomUUID()}@example.test", role = "owner")
		SourceFixtures.insertManifest(tenant, 1, owner, signals = """{"logs":true,"metrics":true,"traces":true}""", activatedAt = kst("2026-07-01T00:00:00"))
		val team = SourceFixtures.insertTeam(tenant, "플랫폼")
		val member = SourceFixtures.insertMember(tenant, "member-${UUID.randomUUID()}@example.test")
		SourceFixtures.insertMembership(team, member, kst("2026-07-01T00:00:00"))
		val installation = SourceFixtures.insertInstallation(tenant, member)
		SourceFixtures.setInstallationTimes(installation, kst("2026-08-01T00:00:00"))
		SourceFixtures.insertSegment(installation, kst("2026-08-01T00:00:00"), kst("2026-09-20T00:00:00"))
		return Org(tenant, team, member, installation)
	}

	private fun usage(org: Org, name: String, at: String, cost: String, team: UUID? = org.team) = Event(
		"${org.tenant}-$name", kst(at), memberId = org.member, teamId = team, sessionId = "session-$name",
		costEstimatedUsd = BigDecimal(cost), pricingVersion = "v1",
	)

	/** 현재 기간: 9/8 팀 30, 9/9 미배정 20. 비교 기간: 9/1 팀 10. */
	private fun withUsage(org: Org): Org {
		SourceFixtures.insertEvents(
			org.tenant,
			usage(org, "current-team", "2026-09-08T10:00:00", "30"),
			usage(org, "current-unassigned", "2026-09-09T10:00:00", "20", team = null),
			usage(org, "previous-team", "2026-09-01T10:00:00", "10"),
		)
		return org
	}

	private fun get(tenant: UUID, path: String): HttpResponse<String> = http.send(
		"/api/v1/organizations/$tenant$path",
		headers = mapOf("Authorization" to TestDashboardAuthenticator.header(DashboardPrincipal(tenant, UUID.randomUUID(), Role.ADMIN))),
	)

	private fun ok(tenant: UUID, path: String): JsonNode {
		val response = get(tenant, path)
		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200)
		return DashboardHttp.json(response)
	}

	private fun point(body: JsonNode, pointer: String, date: String): JsonNode =
		body.at(pointer).toList().single { it.path("date").asString() == date }

	@Test
	@DisplayName("두 기간이 모두 완전 관측이면 비교가 나오고, 사용이 없는 완전한 날은 null 이 아니라 0 이다")
	fun bothPeriodsComplete() {
		val org = withUsage(coveredOrganization())
		val body = ok(org.tenant, "/analytics/overview?$week")

		assertThat(body.at("/meta/dataState").asString()).isEqualTo("ready")
		assertThat(body.at("/meta/currentCoverage/status").asString()).isEqualTo("complete")
		assertThat(body.at("/meta/currentCoverage/observedDays").asInt()).isEqualTo(7)
		// 기간 끝(9/13)의 다음 자정까지 확정이다.
		assertThat(Instant.parse(body.at("/meta/dataThrough").asString())).isEqualTo(kst("2026-09-14T00:00:00"))

		val comparison = body.at("/comparison")
		assertThat(comparison.path("status").asString()).isEqualTo("available")
		assertThat(comparison.path("reason").isNull).isTrue()
		assertThat(comparison.at("/coverage/status").asString()).isEqualTo("complete")
		assertThat(comparison.at("/coverage/observedDays").asInt()).isEqualTo(7)
		assertThat(comparison.path("startDate").asString() to comparison.path("endDate").asString()).isEqualTo("2026-08-31" to "2026-09-06")

		assertThat(body.at("/usage/current/equivalentCostUsd").asString()).isEqualTo("50.000000")
		assertThat(body.at("/usage/current/activeUsers").asLong()).isEqualTo(1)
		assertThat(body.at("/usage/previous/equivalentCostUsd").asString()).isEqualTo("10.000000")
		assertThat(body.at("/usage/previous/sessionCount").asLong()).isEqualTo(1)

		// 일별 추이: 사용이 없던 완전한 날은 0, 사용이 있던 날은 그 값이다. 모든 날이 complete 다.
		assertThat(body.at("/trend/points").toList().map { it.path("observation").asString() }.toSet()).containsExactly("complete")
		val empty = point(body, "/trend/points", "2026-09-07")
		assertThat(empty.path("equivalentCostUsd").asString() to empty.path("totalTokens").asLong()).isEqualTo("0.000000" to 0L)
		assertThat(point(body, "/trend/points", "2026-09-08").path("equivalentCostUsd").asString()).isEqualTo("30.000000")

		// 팀: 이전 값은 같은 팀 ID 로, 이전에 사용이 없던 미배정은 0 이다(신규).
		val top = body.at("/teamUsage/topTeams").single()
		assertThat(top.path("teamId").asString()).isEqualTo(org.team.toString())
		assertThat(top.at("/current/equivalentCostUsd").asString() to top.at("/previous/equivalentCostUsd").asString()).isEqualTo("30.000000" to "10.000000")
		assertThat(body.at("/teamUsage/unassigned/current/equivalentCostUsd").asString()).isEqualTo("20.000000")
		assertThat(body.at("/teamUsage/unassigned/previous/equivalentCostUsd").asString()).isEqualTo("0.000000")
		assertThat(body.at("/teamUsage/unassigned/previous/activeUsers").asLong()).isZero()

		// 응답 키 구조는 화면 요청서의 예시와 같다.
		JsonStructure.assertMatches("overview-response.example.json", body)
	}

	@Test
	@DisplayName("팀 목록·상세·사용자 화면이 같은 snapshot 의 같은 판정으로 이전 값을 낸다")
	fun teamsUseTheSameJudgement() {
		val org = withUsage(coveredOrganization())
		val list = ok(org.tenant, "/analytics/teams?$week")
		val snapshotId = list.at("/meta/snapshotId").asString()
		assertThat(list.at("/meta/dataState").asString()).isEqualTo("ready")
		assertThat(Instant.parse(list.at("/meta/dataThrough").asString())).isEqualTo(kst("2026-09-14T00:00:00"))
		assertThat(list.at("/comparison/status").asString()).isEqualTo("available")
		assertThat(list.at("/totals/previous/equivalentCostUsd").asString()).isEqualTo("10.000000")
		val team = list.at("/teams/items").single()
		assertThat(team.at("/current/equivalentCostUsd").asString() to team.at("/previous/equivalentCostUsd").asString()).isEqualTo("30.000000" to "10.000000")
		assertThat(list.at("/unassigned/previous/equivalentCostUsd").asString()).isEqualTo("0.000000")
		// 팀 추이의 사용이 없던 완전한 날은 0 이다.
		val day = point(team, "/trend", "2026-09-10")
		assertThat(day.path("observation").asString() to day.path("equivalentCostUsd").asString()).isEqualTo("complete" to "0.000000")

		val detail = ok(org.tenant, "/analytics/teams/${org.team}?$week&snapshotId=$snapshotId")
		assertThat(detail.at("/team/previous/equivalentCostUsd").asString()).isEqualTo("10.000000")
		assertThat(detail.at("/comparison/status").asString()).isEqualTo("available")
		val users = ok(org.tenant, "/analytics/teams/${org.team}/users?$week&snapshotId=$snapshotId")
		assertThat(users.at("/summary/usage/equivalentCostUsd").asString()).isEqualTo("30.000000")

		JsonStructure.assertMatches("teams-response.example.json", list)
	}

	@Test
	@DisplayName("하루라도 빈틈이 있으면 그 기간은 partial 이고 비교는 나오지 않는다 — 이전 값은 모두 null")
	fun oneGapBreaksTheCurrentPeriod() {
		val org = withUsage(coveredOrganization())
		// 9/10 12:00~12:10 사이에 데몬 프로세스가 바뀌었다.
		DashboardTestStores.writer.sql("DELETE FROM enrollment.installation_collection_segments WHERE installation_id = :id").param("id", org.installation).update()
		SourceFixtures.insertSegment(org.installation, kst("2026-08-01T00:00:00"), kst("2026-09-10T12:00:00"))
		SourceFixtures.insertSegment(org.installation, kst("2026-09-10T12:10:00"), kst("2026-09-20T00:00:00"))

		val body = ok(org.tenant, "/analytics/overview?$week")
		assertThat(body.at("/meta/dataState").asString()).isEqualTo("partial")
		assertThat(body.at("/meta/currentCoverage/status").asString()).isEqualTo("partial")
		// 9/10 은 완전하지도 관측되지도 않았다. 나머지 여섯 날은 완전하다.
		assertThat(body.at("/meta/currentCoverage/observedDays").asInt()).isEqualTo(6)
		assertThat(Instant.parse(body.at("/meta/dataThrough").asString())).isEqualTo(kst("2026-09-10T00:00:00"))
		assertThat(body.at("/comparison/status").asString() to body.at("/comparison/reason").asString()).isEqualTo("unavailable" to "source_not_available")
		assertThat(body.at("/usage/previous").isNull).isTrue()
		assertThat(body.at("/teamUsage/topTeams").single().path("previous").isNull).isTrue()
		assertThat(body.at("/teamUsage/unassigned/previous").isNull).isTrue()
		// 부분 기간의 합계는 알려진 값이다. 0 으로 채우지 않는다.
		assertThat(body.at("/usage/current/equivalentCostUsd").asString()).isEqualTo("50.000000")
		assertThat(point(body, "/trend/points", "2026-09-10").let { it.path("observation").asString() to it.path("equivalentCostUsd").isNull })
			.isEqualTo("unobserved" to true)
		assertThat(point(body, "/trend/points", "2026-09-11").path("equivalentCostUsd").asString()).isEqualTo("0.000000")
	}

	@Test
	@DisplayName("비교 기간에 사용량을 수집하지 않는 정책이 효력이 있었으면 현재가 완전해도 비교는 나오지 않는다")
	fun previousPeriodWithoutUsagePolicy() {
		val org = withUsage(coveredOrganization())
		val owner = SourceFixtures.insertMember(org.tenant, "policy-${UUID.randomUUID()}@example.test", role = "admin")
		SourceFixtures.insertManifest(org.tenant, 2, owner, active = false, signals = """{"logs":false,"metrics":true,"traces":true}""", activatedAt = kst("2026-09-02T09:00:00"))
		SourceFixtures.insertManifest(org.tenant, 3, owner, active = false, signals = """{"logs":true,"metrics":true,"traces":true}""", activatedAt = kst("2026-09-03T00:00:00"))

		val body = ok(org.tenant, "/analytics/overview?$week")
		assertThat(body.at("/meta/dataState").asString()).isEqualTo("ready")
		assertThat(body.at("/comparison/coverage/status").asString()).isEqualTo("partial")
		assertThat(body.at("/comparison/status").asString()).isEqualTo("unavailable")
		assertThat(body.at("/usage/previous").isNull).isTrue()
		// 현재 기간은 완전하다 — 사용이 없던 날도 0 이다.
		assertThat(point(body, "/trend/points", "2026-09-12").path("equivalentCostUsd").asString()).isEqualTo("0.000000")
	}

	@Test
	@DisplayName("기간 중에 등록한 설치는 등록부터 덮이면 되고, 기간 중에 폐기된 설치가 있으면 그날부터는 완전하지 않다")
	fun registeredAndRevokedInsideThePeriod() {
		val org = withUsage(coveredOrganization())
		val registered = SourceFixtures.insertInstallation(org.tenant, org.member)
		SourceFixtures.setInstallationTimes(registered, kst("2026-09-10T12:00:00"))
		SourceFixtures.insertSegment(registered, kst("2026-09-10T12:00:00"), kst("2026-09-20T00:00:00"))
		assertThat(ok(org.tenant, "/analytics/overview?$week").at("/comparison/status").asString()).isEqualTo("available")

		// 등록부터 1분 늦게 듣기 시작했다 — 그 1분은 덮이지 않았다.
		DashboardTestStores.writer.sql("UPDATE enrollment.installation_collection_segments SET from_at = :from WHERE installation_id = :id")
			.param("from", java.sql.Timestamp.from(kst("2026-09-10T12:01:00"))).param("id", registered).update()
		assertThat(ok(org.tenant, "/analytics/overview?$week").at("/meta/currentCoverage/status").asString()).isEqualTo("partial")

		// 기간 중에 폐기된 설치 — 폐기된 날부터는 마지막 데이터를 확인할 수 없다.
		SourceFixtures.setInstallationTimes(registered, kst("2026-09-10T12:00:00"), revokedAt = kst("2026-09-12T15:00:00"))
		val body = ok(org.tenant, "/analytics/overview?$week")
		assertThat(body.at("/meta/currentCoverage/status").asString()).isEqualTo("partial")
		// 등록한 날(9/10)의 1분 공백은 폐기 전 일이라 그대로다. 폐기된 9/12 도 완전하지 않다.
		assertThat(Instant.parse(body.at("/meta/dataThrough").asString())).isEqualTo(kst("2026-09-10T00:00:00"))
		assertThat(point(body, "/trend/points", "2026-09-11").path("observation").asString()).isEqualTo("complete")
		assertThat(point(body, "/trend/points", "2026-09-12").path("observation").asString()).isEqualTo("unobserved")
		assertThat(point(body, "/trend/points", "2026-09-13").path("observation").asString()).isEqualTo("complete")
	}

	@Test
	@DisplayName("완전성의 근거가 없는 조직은 이전과 같다 — partial 이고 비교·dataThrough 가 없다")
	fun withoutEvidence() {
		val tenant = DashboardTestStores.insertTenant()
		SourceFixtures.setSummary(tenant, firstReceivedAt = kst("2026-08-01T00:00:00"))
		SourceFixtures.insertEvents(tenant, Event("$tenant-a", kst("2026-09-08T10:00:00"), costEstimatedUsd = BigDecimal("3"), pricingVersion = "v1"))
		val body = ok(tenant, "/analytics/overview?$week")
		assertThat(body.at("/meta/dataState").asString()).isEqualTo("partial")
		assertThat(body.at("/meta/currentCoverage/status").asString() to body.at("/meta/currentCoverage/observedDays").asInt()).isEqualTo("partial" to 1)
		assertThat(body.at("/meta/dataThrough").isNull).isTrue()
		assertThat(body.at("/comparison/status").asString()).isEqualTo("unavailable")
		assertThat(point(body, "/trend/points", "2026-09-08").path("observation").asString()).isEqualTo("partial")
		assertThat(point(body, "/trend/points", "2026-09-09").let { it.path("observation").asString() to it.path("totalTokens").isNull }).isEqualTo("unobserved" to true)
	}

	@Test
	@DisplayName("판정은 snapshot 에 고정된다 — 근거가 바뀌어도 같은 snapshot 은 같은 답이고, 판정 규칙 전의 snapshot 은 409 다")
	fun judgementIsFixedInTheSnapshot() {
		val org = withUsage(coveredOrganization())
		val first = ok(org.tenant, "/analytics/teams?$week")
		val snapshotId = first.at("/meta/snapshotId").asString()
		assertThat(first.at("/comparison/status").asString()).isEqualTo("available")

		// 근거가 사라져도 같은 snapshot 은 build 때의 판정을 쓴다.
		DashboardTestStores.writer.sql("DELETE FROM enrollment.installation_collection_segments WHERE installation_id = :id").param("id", org.installation).update()
		val detail = ok(org.tenant, "/analytics/teams/${org.team}?$week&snapshotId=$snapshotId")
		assertThat(detail.at("/comparison/status").asString()).isEqualTo("available")
		assertThat(detail.at("/meta/dataThrough").asString()).isEqualTo(first.at("/meta/dataThrough").asString())
		// 새 snapshot 은 새 근거로 판정한다.
		assertThat(ok(org.tenant, "/analytics/teams?$week").at("/comparison/status").asString()).isEqualTo("unavailable")

		// 완전한 날짜를 고정하지 않던 판정 규칙의 snapshot 은 다시 쓰지 않는다.
		DashboardTestStores.writer.sql("UPDATE dashboard_cache.snapshots SET query_contract = 'dashboard-v1' WHERE snapshot_id = :id").param("id", snapshotId).update()
		val stale = get(org.tenant, "/analytics/teams/${org.team}?$week&snapshotId=$snapshotId")
		assertThat(stale.statusCode()).isEqualTo(409)
		assertThat(DashboardHttp.json(stale).at("/error/code").asString()).isEqualTo("snapshot_expired")
	}
}
