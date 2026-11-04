package com.team376.pulsemetry.dashboard.support

import com.team376.pulsemetry.dashboard.alert.AlertEvaluator
import com.team376.pulsemetry.dashboard.authentication.DashboardPrincipal
import com.team376.pulsemetry.dashboard.authentication.Role
import com.team376.pulsemetry.dashboard.request.QueryReader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** PROJ-316·317·318의 화면 촬영에만 명시적으로 사용하는 실제 API·PostgreSQL·ClickHouse 하네스다. */
@EnabledIfEnvironmentVariable(named = "PULSEMETRY_VERIFICATION_BROWSER", matches = "true")
class VerificationBrowserHarnessTest : AbstractDashboardApiTest() {
	@LocalServerPort private var browserPort: Int = 0
	@Autowired private lateinit var evaluator: AlertEvaluator
	private val writer get() = DashboardTestStores.writer
	private val start = LocalDate.parse("2026-09-01")
	private val from = start.atStartOfDay(QueryReader.SEOUL).toInstant()
	private val end = start.plusDays(29)
	private val period = "startDate=$start&endDate=$end&compare=none"
	private data class Scenario(val tenant: UUID, val team: UUID, val member: UUID, val extra: Map<String, Any?> = emptyMap()) {
		val authorization get() = TestDashboardAuthenticator.header(DashboardPrincipal(tenant, member, Role.ADMIN))
		fun config() = mapOf("organizationId" to tenant.toString(), "teamId" to team.toString(), "memberId" to member.toString(),
			"authorization" to authorization) + extra
	}

	@Test
	fun serveIsolatedVerificationScenarios() {
		SourceFixtures.completeBackfill()
		val seededAt = Instant.now()
		val main = mainScenario(seededAt)
		val zero = missingScenario("0원·0토큰 사용 검증", zero = true)
		val unpriced = missingScenario("미환산·세션 결손 검증", zero = false)
		val collection = seedCollection(main, seededAt)
		val overview = get(main, "/analytics/overview?$period&bucket=auto")
		assertThat(overview.at("/summary/equivalentCostUsd/value").asString()).isEqualTo("98.123459")
		assertThat(overview.at("/summary/totalTokens/value").asLong()).isEqualTo(770)
		assertThat(overview.at("/summary/sessionCount/value").asLong()).isEqualTo(6)
		val teams = get(main, "/analytics/teams?$period&bucket=auto&sort=session")
		assertThat(teams.at("/teams/items/0/teamId").asString()).isEqualTo(main.team.toString())
		val snapshot = teams.at("/meta/snapshotId").asString()
		val users = get(main, "/analytics/teams/${main.team}/users?$period&snapshotId=$snapshot&sort=session&order=desc")
		assertThat(users.at("/users/items/0/usage/sessionCount/value").asLong()).isEqualTo(2)
		assertThat(users.at("/users/items/1/usage/sessionCount/value").asLong()).isEqualTo(1)
		for ((scenario, expectedUnpriced) in listOf(zero to 0L, unpriced to 1L)) {
			val body = get(scenario, "/analytics/teams?$period&bucket=auto")
			assertThat(body.at("/unassigned/metrics/unpricedUsageRows").asLong()).isEqualTo(expectedUnpriced)
			assertThat(body.at("/unassigned/metrics/sessionCount/value").isNull).isTrue()
			assertThat(body.at("/unassigned/metrics/pricedUsageRows").asLong() + body.at("/unassigned/metrics/unpricedUsageRows").asLong()).isEqualTo(1)
		}
		val ingest = get(main, "/ingest-status")
		assertThat(ingest.at("/transmissionLoss/payloadCount").asLong()).isEqualTo(7)
		val lossEnd = ingest.at("/transmissionLoss/windowEnd").asString()
		val loss = get(main, "/ingest-status/loss-installations?windowEnd=$lossEnd&limit=20")
		assertThat(loss.at("/installations/totalCount").asInt()).isEqualTo(2)
		val alerts = get(main, "/alerts?category=collection")
		assertThat(alerts.at("/alerts/totalCount").asInt()).isEqualTo(1)
		val alertId = alerts.at("/alerts/items/0/alertId").asString()
		get(main, "/alerts/$alertId")
		val today = seededAt.atZone(QueryReader.SEOUL).toLocalDate()
		val current = get(main, "/analytics/overview?startDate=$today&endDate=$today&compare=none&bucket=auto")
		assertThat(current.at("/summary/equivalentCostUsd/value").asString()).isEqualTo("0.750000")
		val output = Path.of(System.getenv("PULSEMETRY_VERIFICATION_BROWSER_FILE") ?: "/tmp/proj318-verification-api.json")
		val stop = Path.of("${output}.stop")
		Files.createDirectories(output.parent)
		Files.deleteIfExists(stop)
		val config = mapOf(
			"baseURL" to "http://127.0.0.1:$browserPort", "startDate" to start.toString(), "endDate" to end.toString(),
			"seededAt" to seededAt.toString(), "today" to today.toString(), "futureDate" to today.plusDays(1).toString(),
			"todaySnapshotAsOf" to current.at("/meta/trendAggregation/asOf").asString(), "clockMode" to "system",
			"scenarios" to mapOf("main" to main.config(), "zeroUsage" to zero.config(), "unpricedUsage" to unpriced.config()),
			"collection" to (collection + mapOf("alertId" to alertId, "initialLossWindowEnd" to lossEnd, "lossPayloadCount" to 7,
				"lossInstallationCount" to 2, "openCollectionAlerts" to 1)),
			"limitations" to listOf("인증 포트만 테스트 인증으로 대체", "enrollment 확인 명령과 운영 SSO는 범위 밖", "손실 상세는 최신 ingest-status의 windowEnd를 사용"),
		) + main.config()
		Files.createFile(output, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
		Files.writeString(output, JsonMapper.builder().build().writeValueAsString(config))
		val minutes = (System.getenv("PULSEMETRY_VERIFICATION_BROWSER_MINUTES")?.toLongOrNull() ?: 45L).coerceIn(1, 180)
		val deadline = Instant.now().plusSeconds(minutes * 60)
		try {
			while (!Files.exists(stop) && Instant.now().isBefore(deadline)) Thread.sleep(200)
		} finally {
			Files.deleteIfExists(output)
			Files.deleteIfExists(stop)
		}
	}

	private fun mainScenario(now: Instant): Scenario {
		val tenant = DashboardTestStores.insertTenant(name = "Pulsemetry 실 API 검증")
		val original = SourceFixtures.insertTeam(tenant, "자동 집계 검증팀")
		val team = SourceFixtures.insertTeam(tenant, "플랫폼팀")
		val expensive = SourceFixtures.insertTeam(tenant, "제품팀")
		val admin = SourceFixtures.insertMember(tenant, "auto-trend@example.test", role = "admin", displayName = "집계 검증자")
		val alice = SourceFixtures.insertMember(tenant, "alice@example.test", displayName = "김지윤")
		val bob = SourceFixtures.insertMember(tenant, "bob@example.test", displayName = "이도현")
		val carol = SourceFixtures.insertMember(tenant, "carol@example.test", displayName = "박서연")
		SourceFixtures.insertMember(tenant, "idle@example.test", displayName = "미관측 구성원")
		for ((t, m) in listOf(original to admin, team to alice, team to bob, expensive to carol)) SourceFixtures.insertMembership(t, m, from.minusSeconds(1))
		SourceFixtures.setSummary(tenant, firstReceivedAt = from, lastReceivedAt = now.minusSeconds(30))
		SourceFixtures.insertEvents(tenant,
			SourceFixtures.Event("original-first", from.plusSeconds(3600), teamId = original, memberId = admin, sessionId = "original-shared", costEstimatedUsd = BigDecimal("1.123456")),
			SourceFixtures.Event("original-later", from.plusSeconds(86400 + 7200), teamId = original, memberId = admin, sessionId = "original-shared", costEstimatedUsd = BigDecimal("2.000001")),
			SourceFixtures.Event("original-new", from.plusSeconds(29 * 86400 + 3600), teamId = original, memberId = admin, sessionId = "original-second", costEstimatedUsd = BigDecimal("3.000002")),
			SourceFixtures.Event("alice-1", from.plusSeconds(3600), teamId = team, memberId = alice, sessionId = "a1", costEstimatedUsd = BigDecimal("2")),
			SourceFixtures.Event("alice-2", from.plusSeconds(7200), teamId = team, memberId = alice, sessionId = "a2", costEstimatedUsd = BigDecimal("4")),
			SourceFixtures.Event("bob-1", from.plusSeconds(86400 + 3600), teamId = team, memberId = bob, sessionId = "b1", costEstimatedUsd = BigDecimal("6")),
			SourceFixtures.Event("carol-1", from.plusSeconds(86400 + 7200), teamId = expensive, memberId = carol, sessionId = "c1", costEstimatedUsd = BigDecimal("80")),
			SourceFixtures.Event("today", maxOf(now.atZone(QueryReader.SEOUL).toLocalDate().atStartOfDay(QueryReader.SEOUL).toInstant(), now.minusSeconds(120)), teamId = team, memberId = alice, sessionId = "today", costEstimatedUsd = BigDecimal("0.75")),
		)
		return Scenario(tenant, team, admin, mapOf("organizationName" to "Pulsemetry 실 API 검증", "originalTeamId" to original.toString(),
			"expensiveTeamId" to expensive.toString(), "equivalentCostUsd" to "98.123459", "totalTokens" to 770, "sessions" to 6,
			"platformCostUsd" to "12.000000", "platformTokens" to 330, "platformSessions" to 3,
			"originalCostUsd" to "6.123459", "originalTokens" to 330, "originalSessions" to 2,
			"sessionSortTeamIds" to listOf(team, original, expensive).map(UUID::toString),
			"sessionSortUserIds" to listOf(alice, bob).map(UUID::toString), "sessionSortUserCounts" to listOf(2, 1),
			"todayCostUsd" to "0.750000", "todayTokens" to 110, "todaySessions" to 1))
	}

	private fun missingScenario(name: String, zero: Boolean): Scenario {
		val tenant = DashboardTestStores.insertTenant(name = name)
		val team = SourceFixtures.insertTeam(tenant, "플랫폼팀")
		val admin = SourceFixtures.insertMember(tenant, "admin@example.test", role = "admin", displayName = "관리자")
		val member = SourceFixtures.insertMember(tenant, "unassigned@example.test", displayName = if (zero) "0원 사용 구성원" else "미환산 사용 구성원")
		val recent = Instant.now().minusSeconds(120)
		SourceFixtures.insertMembership(team, admin, from.minusSeconds(1))
		SourceFixtures.setSummary(tenant, firstReceivedAt = from, lastReceivedAt = Instant.now().minusSeconds(30))
		SourceFixtures.insertEvents(tenant,
			SourceFixtures.Event("known", from.plusSeconds(3600), teamId = team, memberId = admin, sessionId = "known", costEstimatedUsd = BigDecimal("10")),
			SourceFixtures.Event("unassigned", from.plusSeconds(7200), memberId = member, sessionId = null, costEstimatedUsd = if (zero) BigDecimal.ZERO else null,
				tokensInput = if (zero) 0 else 100, tokensInputUncached = if (zero) 0 else 100, tokensOutput = if (zero) 0 else 10, tokensTotalDerived = if (zero) 0 else 110),
			// 구성원 화면의 최근 7일 범위에도 같은 의미의 기록을 별도로 제공한다. 9월 집계는 바꾸지 않는다.
			SourceFixtures.Event("known-recent", recent, teamId = team, memberId = admin, sessionId = "known-recent", costEstimatedUsd = BigDecimal("10")),
			SourceFixtures.Event("unassigned-recent", recent, memberId = member, sessionId = null, costEstimatedUsd = if (zero) BigDecimal.ZERO else null,
				tokensInput = if (zero) 0 else 100, tokensInputUncached = if (zero) 0 else 100, tokensOutput = if (zero) 0 else 10, tokensTotalDerived = if (zero) 0 else 110),
		)
		return Scenario(tenant, team, admin, mapOf("organizationName" to name, "unassignedMemberId" to member.toString(),
			"unassignedCostUsd" to if (zero) "0.000000" else null, "unassignedTokens" to if (zero) 0 else 110,
			"unassignedSessions" to null, "unassignedUsageRows" to 1, "unassignedPricedUsageRows" to if (zero) 1 else 0,
			"unassignedUnpricedUsageRows" to if (zero) 0 else 1, "unassignedLastUsedAt" to recent.toString(), "historicalUnassignedLastUsedAt" to from.plusSeconds(7200).toString()))
	}

	private fun seedCollection(scenario: Scenario, now: Instant): Map<String, Any?> {
		val silent = SourceFixtures.insertInstallation(scenario.tenant, scenario.member)
		val retired = SourceFixtures.insertInstallation(scenario.tenant, scenario.member, status = "revoked")
		val healthy = SourceFixtures.insertInstallation(scenario.tenant, scenario.member)
		for ((id, name) in listOf(silent to "platform-laptop", retired to "retired-workstation", healthy to "active-desktop")) {
			writer.sql("UPDATE enrollment.installations SET hostname=:name WHERE id=:id").param("name", name).param("id", id).update()
			SourceFixtures.setInstallationTimes(id, now.minusSeconds(12 * 86400), now.minusSeconds(3600).takeIf { id == retired })
		}
		SourceFixtures.setHeartbeat(silent, now.minusSeconds(8 * 86400))
		SourceFixtures.setHeartbeat(healthy, now)
		writer.sql("UPDATE enrollment.installation_loss_tracking SET started_at=:at WHERE stopped_at IS NULL").param("at", Timestamp.from(now.minusSeconds(12 * 86400))).update()
		for ((id, count) in listOf(silent to 4L, retired to 3L)) {
			writer.sql("INSERT INTO enrollment.installation_loss_events(tenant_id,installation_id,run_id,payload_count,observed_at) VALUES(:tenant,:id,'browser-run',:count,:at)")
				.param("tenant", scenario.tenant).param("id", id).param("count", count).param("at", Timestamp.from(now.minusSeconds(7200))).update()
		}
		check(evaluator.evaluate(scenario.tenant))
		return mapOf("silentInstallationId" to silent.toString(), "retiredInstallationId" to retired.toString(), "healthyInstallationId" to healthy.toString())
	}

	private fun get(scenario: Scenario, path: String): JsonNode {
		val response = http.send("/api/v1/organizations/${scenario.tenant}$path", headers = mapOf("Authorization" to scenario.authorization))
		assertThat(response.statusCode()).describedAs("%s: %s", path, response.body()).isEqualTo(200)
		return DashboardHttp.json(response)
	}
}
