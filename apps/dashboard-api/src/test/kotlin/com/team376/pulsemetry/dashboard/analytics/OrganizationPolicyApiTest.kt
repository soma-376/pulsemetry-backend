package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.authentication.DashboardPrincipal
import com.team376.pulsemetry.dashboard.authentication.Role
import com.team376.pulsemetry.dashboard.request.QueryReader
import com.team376.pulsemetry.dashboard.support.AbstractDashboardApiTest
import com.team376.pulsemetry.dashboard.support.DashboardHttp
import com.team376.pulsemetry.dashboard.support.DashboardTestStores
import com.team376.pulsemetry.dashboard.support.JsonStructure
import com.team376.pulsemetry.dashboard.support.SourceFixtures
import com.team376.pulsemetry.dashboard.support.TestDashboardAuthenticator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import com.team376.pulsemetry.persistence.enrollment.operation.OperationKind
import com.team376.pulsemetry.persistence.enrollment.operation.OperationStore
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.LocalDateTime
import java.util.UUID

/**
 * 조직 정책 설정의 조회 (ADR 0046) — 설정의 정책 조각, 구성원 화면의 `policy`, 회수 후보가 같은 조직의 같은 값·판을 쓴다.
 * 기대값은 ADR 의 규칙에서 쓴다: 저장값이 없으면 회수 기준은 이 서버의 기본 설정(테스트 레지스트리 14일)·판 0, 집계 보존은 무기한(null).
 * 설정의 `version` 은 여전히 manifest 판이다(정책 설정의 판과 별개).
 */
class OrganizationPolicyApiTest : AbstractDashboardApiTest() {

	@BeforeEach
	fun backfillDone() { SourceFixtures.completeBackfill() }

	private val week = "startDate=2026-09-07&endDate=2026-09-13"

	private fun ok(tenant: UUID, path: String): JsonNode {
		val response = http.send("/api/v1/organizations/$tenant$path",
			headers = mapOf("Authorization" to TestDashboardAuthenticator.header(DashboardPrincipal(tenant, UUID.randomUUID(), Role.ADMIN))))
		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200)
		return DashboardHttp.json(response)
	}

	private data class Org(val tenant: UUID, val admin: UUID)

	private fun organization(): Org {
		val tenant = DashboardTestStores.insertTenant()
		SourceFixtures.setSummary(tenant, firstReceivedAt = LocalDateTime.parse("2026-09-01T00:00:00").atZone(QueryReader.SEOUL).toInstant())
		val admin = SourceFixtures.insertMember(tenant, "admin-${UUID.randomUUID()}@example.test", role = "admin")
		SourceFixtures.insertManifest(tenant, 2, admin)
		return Org(tenant, admin)
	}

	/** enrollment 의 저장 명령이 남기는 행을 직접 넣는다 — 이 앱은 읽기만 한다. */
	private fun store(org: Org, reclaim: Int?, retention: Int?, version: Long, at: Instant) {
		DashboardTestStores.writer.sql("""INSERT INTO enrollment.organization_policy_settings(tenant_id,reclaim_idle_days,aggregate_retention_months,version,updated_at,updated_by)
			VALUES (:tenant,:reclaim,:retention,:version,:at,:by)""")
			.param("tenant", org.tenant).param("reclaim", reclaim, java.sql.Types.SMALLINT).param("retention", retention, java.sql.Types.SMALLINT)
			.param("version", version).param("at", java.sql.Timestamp.from(at)).param("by", org.admin).update()
	}

	/** 세 조회의 (회수 기준, 판). */
	private fun reclaimPolicies(org: Org): List<Pair<Int, Long>> {
		val settings = ok(org.tenant, "/settings").path("collectionPolicy")
		val members = ok(org.tenant, "/members/dashboard?$week").path("policy")
		val candidates = ok(org.tenant, "/seat-reclaim-candidates")
		assertThat(candidates.path("idleDays").asInt()).isEqualTo(candidates.at("/policy/idleDays").asInt())
		return listOf(
			settings.path("reclaimIdleDays").asInt() to settings.path("settingsVersion").asLong(),
			members.path("idleDays").asInt() to members.path("version").asLong(),
			candidates.at("/policy/idleDays").asInt() to candidates.at("/policy/version").asLong(),
		)
	}

	@Test
	@DisplayName("저장하지 않은 조직은 서버 기본 회수 기준·판 0·무기한 보존이고, 저장할 수 있는 값을 함께 낸다")
	fun unset() {
		val org = organization()
		val policy = ok(org.tenant, "/settings").path("collectionPolicy")
		assertThat(policy.path("version").asLong()).isEqualTo(2)
		assertThat(policy.path("reclaimIdleDaysSource").asString()).isEqualTo("default")
		assertThat(listOf("aggregateRetentionMonths", "rawContentRetentionDays", "settingsUpdatedAt", "settingsUpdatedBy").map { policy.path(it).isNull }).containsOnly(true)
		assertThat(policy.at("/options/reclaimIdleDays").toList().map { it.asInt() }).containsExactly(7, 14, 30, 60)
		assertThat(policy.at("/options/aggregateRetentionMonths").toList().map { if (it.isNull) null else it.asInt() }).containsExactly(12, 24, 36, null)
		assertThat(reclaimPolicies(org)).containsOnly(14 to 0L)
		JsonStructure.assertMatches("settings-response.example.json", ok(org.tenant, "/settings"))
	}

	@Test
	@DisplayName("저장값이 있으면 설정·구성원·회수 후보가 같은 회수 기준과 그 판을 쓴다 — manifest 판과 따로다")
	fun stored() {
		val org = organization()
		val at = Instant.parse("2026-09-20T03:00:00Z")
		store(org, reclaim = 30, retention = 24, version = 5, at = at)
		val policy = ok(org.tenant, "/settings").path("collectionPolicy")
		assertThat(policy.path("version").asLong()).isEqualTo(2)
		assertThat(listOf(policy.path("reclaimIdleDays").asInt(), policy.path("aggregateRetentionMonths").asInt())).containsExactly(30, 24)
		assertThat(policy.path("reclaimIdleDaysSource").asString()).isEqualTo("organization")
		assertThat(Instant.parse(policy.path("settingsUpdatedAt").asString())).isEqualTo(at)
		assertThat(policy.path("settingsUpdatedBy").asString()).isEqualTo(org.admin.toString())
		assertThat(reclaimPolicies(org)).containsOnly(30 to 5L)
	}

	@Test
	@DisplayName("설정은 그 조직의 가장 최근 보존 정리 작업을 가리킨다 — 다른 종류·다른 조직의 작업은 아니다(ADR 0047)")
	fun latestCleanup() {
		val org = organization()
		assertThat(ok(org.tenant, "/settings").at("/collectionPolicy/cleanupOperationId").isNull).isTrue()
		val dataSource = DriverManagerDataSource(DashboardTestStores.postgres.jdbcUrl, DashboardTestStores.postgres.username, DashboardTestStores.postgres.password)
		val jdbc = JdbcClient.create(dataSource)
		val manager = DataSourceTransactionManager(dataSource)
		fun operations(at: String) = OperationStore(jdbc, manager, Clock.fixed(Instant.parse(at), ZoneOffset.UTC))
		val kind = OperationKind.RETENTION_CLEANUP
		val older = operations("2026-09-20T00:00:00Z").create(org.tenant, kind, org.admin, listOf("analysis_source")).id
		val newer = operations("2026-09-21T00:00:00Z").create(org.tenant, kind, org.admin, listOf("analysis_source")).id
		operations("2026-09-22T00:00:00Z").create(org.tenant, OperationKind.INSTALLATION_NOTIFICATION, org.admin, listOf("i"))
		val other = organization()
		operations("2026-09-23T00:00:00Z").create(other.tenant, kind, other.admin, listOf("analysis_source"))

		assertThat(ok(org.tenant, "/settings").at("/collectionPolicy/cleanupOperationId").asString()).isEqualTo(newer.toString()).isNotEqualTo(older.toString())
	}

	@Test
	@DisplayName("집계 보존만 저장한 조직은 회수 기준이 서버 기본값이고 판은 저장된 판이다")
	fun retentionOnly() {
		val org = organization()
		store(org, reclaim = null, retention = 12, version = 2, at = Instant.parse("2026-09-20T03:00:00Z"))
		val policy = ok(org.tenant, "/settings").path("collectionPolicy")
		assertThat(policy.path("aggregateRetentionMonths").asInt()).isEqualTo(12)
		assertThat(policy.path("reclaimIdleDaysSource").asString()).isEqualTo("default")
		assertThat(reclaimPolicies(org)).containsOnly(14 to 2L)
		// 다른 조직의 저장값은 섞이지 않는다.
		assertThat(reclaimPolicies(organization())).containsOnly(14 to 0L)
	}
}
