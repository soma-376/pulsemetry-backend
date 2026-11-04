package com.team376.pulsemetry.dashboard.support

import com.team376.pulsemetry.dashboard.authentication.DashboardPrincipal
import com.team376.pulsemetry.dashboard.authentication.Role
import com.team376.pulsemetry.dashboard.request.QueryReader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.boot.test.web.server.LocalServerPort
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate

/**
 * 명시적으로 켠 로컬 브라우저 검증에서만 살아 있는 실제 API·DB를 제공한다.
 * 인증만 테스트 포트로 대체하며 운영 앱에는 포함되지 않는다. 종료 파일 또는 20분 후 저장소가 정리된다.
 */
@EnabledIfEnvironmentVariable(named = "PULSEMETRY_AUTO_TREND_BROWSER", matches = "true")
class AutoTrendBrowserHarnessTest : AbstractDashboardApiTest() {
	@LocalServerPort
	private var browserPort: Int = 0

	@Test
	fun serveIsolatedAnalytics() {
		SourceFixtures.completeBackfill()
		val tenant = DashboardTestStores.insertTenant()
		val team = SourceFixtures.insertTeam(tenant, "자동 집계 검증팀")
		val member = SourceFixtures.insertMember(tenant, "auto-trend@example.test", role = "admin", displayName = "집계 검증자")
		val start = LocalDate.parse("2026-09-01")
		val from = start.atStartOfDay(QueryReader.SEOUL).toInstant()
		SourceFixtures.insertMembership(team, member, from.minusSeconds(1))
		SourceFixtures.setSummary(tenant, firstReceivedAt = from, lastReceivedAt = from.plusSeconds(86400))
		SourceFixtures.insertEvents(tenant,
			SourceFixtures.Event("browser-first", from.plusSeconds(3600), teamId = team, memberId = member,
				sessionId = "shared", costEstimatedUsd = BigDecimal("1.123456")),
			SourceFixtures.Event("browser-later", from.plusSeconds(86400 + 7200), teamId = team, memberId = member,
				sessionId = "shared", costEstimatedUsd = BigDecimal("2.000001")),
			SourceFixtures.Event("browser-new-session", from.plusSeconds(29 * 86400 + 3600), teamId = team, memberId = member,
				sessionId = "second", costEstimatedUsd = BigDecimal("3.000002")),
		)
		val output = Path.of(System.getenv("PULSEMETRY_AUTO_TREND_BROWSER_FILE") ?: "/tmp/proj317-browser-api.json")
		val stop = Path.of("${output}.stop")
		Files.deleteIfExists(stop)
		Files.writeString(output, JsonMapper.builder().build().writeValueAsString(mapOf(
			"baseURL" to "http://127.0.0.1:$browserPort",
			"organizationId" to tenant.toString(), "teamId" to team.toString(), "memberId" to member.toString(),
			"authorization" to TestDashboardAuthenticator.header(DashboardPrincipal(tenant, member, Role.ADMIN)),
			"startDate" to start.toString(), "endDate" to start.plusDays(29).toString(),
			"equivalentCostUsd" to "6.123459", "totalTokens" to 330, "sessions" to 2,
		)))
		val deadline = Instant.now().plusSeconds(20 * 60)
		try {
			while (!Files.exists(stop) && Instant.now().isBefore(deadline)) Thread.sleep(200)
		} finally {
			Files.deleteIfExists(output)
			Files.deleteIfExists(stop)
		}
	}
}
