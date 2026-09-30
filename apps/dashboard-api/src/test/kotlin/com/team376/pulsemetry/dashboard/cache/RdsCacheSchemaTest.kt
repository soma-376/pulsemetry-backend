package com.team376.pulsemetry.dashboard.cache

import com.team376.pulsemetry.dashboard.support.DashboardTestStores
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID

/**
 * RDS `dashboard_cache` DDL (ADR 0023 §2). 이 클래스만의 데이터베이스에 적용해 앱 컨텍스트의 스키마와 섞이지 않게 한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RdsCacheSchemaTest {

	private val databaseName = "cache_schema_${System.nanoTime()}"
	private lateinit var jdbc: JdbcClient
	private var firstRun = 0
	private var secondRun = 0

	@BeforeAll
	fun migrateTwice() {
		DashboardTestStores.writer.sql("CREATE DATABASE $databaseName").update()
		val postgres = DashboardTestStores.postgres
		val url = postgres.jdbcUrl.replaceAfterLast('/', databaseName)
		val dataSource = DriverManagerDataSource(url, postgres.username, postgres.password)
		firstRun = RdsCacheSchema(dataSource).migrate()
		secondRun = RdsCacheSchema(dataSource).migrate()
		jdbc = JdbcClient.create(dataSource)
	}

	@Test
	@DisplayName("두 번째 적용은 아무것도 하지 않는다 — 이력은 dashboard_cache.flyway_schema_history")
	fun migrationIsRecorded() {
		// V1(manifest·참조 복제), V2(완전한 날짜 — ADR 0042), V3(벤더 관측 고정 — ADR 0044), V4(관측 제품 매핑 복제 — ADR 0045).
		assertThat(firstRun).isEqualTo(4)
		assertThat(secondRun).isZero()
		// 스키마 생성 행(type = SCHEMA)은 Flyway 가 스키마를 만들 때 따로 남긴다 — 마이그레이션만 센다.
		assertThat(
			jdbc.sql("SELECT version FROM dashboard_cache.flyway_schema_history WHERE success AND type = 'SQL'").query(String::class.java).list(),
		).containsExactly("1", "2", "3", "4")
	}

	@Test
	@DisplayName("manifest 와 참조 복제 테이블 넷(팀·구성원·제품 매핑)·완전한 날짜 테이블·벤더 관측 고정 테이블 둘, native enum 넷")
	fun tablesAndEnums() {
		val tables = jdbc.sql(
			"SELECT table_name FROM information_schema.tables WHERE table_schema = 'dashboard_cache' AND table_name <> 'flyway_schema_history' ORDER BY 1",
		).query(String::class.java).list()
		assertThat(tables).containsExactly("snapshot_complete_days", "snapshot_members", "snapshot_products", "snapshot_teams", "snapshots", "vendor_observation_sets", "vendor_observations")

		fun labels(type: String) = jdbc.sql(
			"SELECT e.enumlabel FROM pg_enum e JOIN pg_type t ON t.oid = e.enumtypid JOIN pg_namespace n ON n.oid = t.typnamespace " +
				"WHERE n.nspname = 'dashboard_cache' AND t.typname = :type ORDER BY e.enumsortorder",
		).param("type", type).query(String::class.java).list()
		assertThat(labels("snapshot_status")).containsExactly("building", "ready", "failed")
		assertThat(labels("compare_mode")).containsExactly("prev_week", "prev_period", "none")
		assertThat(labels("member_role")).containsExactly("owner", "admin", "member")
		assertThat(labels("member_status")).containsExactly("invited", "active", "suspended")
	}

	@Test
	@DisplayName("manifest 의 열은 ADR 0023 §2 표와 같다")
	fun manifestColumns() {
		val columns = jdbc.sql(
			"SELECT column_name FROM information_schema.columns WHERE table_schema = 'dashboard_cache' AND table_name = 'snapshots' ORDER BY ordinal_position",
		).query(String::class.java).list()

		assertThat(columns).containsExactly(
			"snapshot_id", "build_id", "tenant_id", "requested_by", "access_scope",
			"query_contract", "time_zone", "compare_mode", "current_start_date", "current_end_date", "previous_start_date", "previous_end_date",
			"as_of", "deleted_before", "policy_epoch", "model_resolution_version", "pricing_versions",
			"status", "created_at", "build_deadline", "ready_at", "expires_at", "failed_at", "failure_reason", "invalidated_at", "invalidation_reason",
			"usage_rows", "observed_day_rows",
		)
	}

	private fun insertBuilding(
		snapshotId: String = randomId(),
		compare: String = "prev_week",
		previous: Pair<String, String>? = "2026-08-31" to "2026-09-06",
	): String {
		jdbc.sql(
			"""
			INSERT INTO dashboard_cache.snapshots (
			    snapshot_id, build_id, tenant_id, requested_by, access_scope, query_contract, time_zone, compare_mode,
			    current_start_date, current_end_date, previous_start_date, previous_end_date,
			    as_of, policy_epoch, model_resolution_version, status, created_at, build_deadline)
			VALUES (:id, :build, :tenant, :member, 'organization', 'dashboard-v1', 'Asia/Seoul', CAST(:compare AS dashboard_cache.compare_mode),
			    DATE '2026-09-07', DATE '2026-09-13', CAST(:previousStart AS date), CAST(:previousEnd AS date),
			    now(), 0, 'model-id-v1', 'building', now(), now() + interval '1 minute')
			""".trimIndent(),
		)
			.param("id", snapshotId)
			.param("build", UUID.randomUUID())
			.param("tenant", UUID.randomUUID())
			.param("member", UUID.randomUUID())
			.param("compare", compare)
			.param("previousStart", previous?.first)
			.param("previousEnd", previous?.second)
			.update()
		return snapshotId
	}

	private fun randomId(): String = UUID.randomUUID().toString().replace("-", "").take(22)

	@Test
	@DisplayName("building manifest 는 행 수·공개 시각 없이 들어간다")
	fun buildingIsAccepted() {
		val id = insertBuilding()

		assertThat(jdbc.sql("SELECT status::text FROM dashboard_cache.snapshots WHERE snapshot_id = :id").param("id", id).query(String::class.java).single())
			.isEqualTo("building")
	}

	@Test
	@DisplayName("ready 는 공개 시각·만료·행 수가 있어야 한다 — 행 0개도 ready 다")
	fun readyRequiresPublicationFields() {
		val id = insertBuilding()

		assertThatThrownBy {
			jdbc.sql("UPDATE dashboard_cache.snapshots SET status = 'ready' WHERE snapshot_id = :id").param("id", id).update()
		}.isInstanceOf(DataIntegrityViolationException::class.java)

		val updated = jdbc.sql(
			"UPDATE dashboard_cache.snapshots SET status = 'ready', ready_at = now(), expires_at = now() + interval '10 minutes', " +
				"usage_rows = 0, observed_day_rows = 0 WHERE snapshot_id = :id",
		).param("id", id).update()
		assertThat(updated).isEqualTo(1)
	}

	@Test
	@DisplayName("failed 는 실패 시각이 있어야 한다")
	fun failedRequiresFailedAt() {
		val id = insertBuilding()

		assertThatThrownBy {
			jdbc.sql("UPDATE dashboard_cache.snapshots SET status = 'failed' WHERE snapshot_id = :id").param("id", id).update()
		}.isInstanceOf(DataIntegrityViolationException::class.java)
	}

	@Test
	@DisplayName("비교 기간은 compare_mode 가 none 일 때만 없다")
	fun previousPeriodFollowsCompareMode() {
		insertBuilding(compare = "none", previous = null)
		assertThatThrownBy { insertBuilding(compare = "none") }.isInstanceOf(DataIntegrityViolationException::class.java)
		assertThatThrownBy { insertBuilding(compare = "prev_week", previous = null) }.isInstanceOf(DataIntegrityViolationException::class.java)
	}

	@Test
	@DisplayName("snapshot_id 는 base64url 22자다")
	fun snapshotIdFormat() {
		assertThatThrownBy { insertBuilding(snapshotId = "short") }.isInstanceOf(DataIntegrityViolationException::class.java)
		assertThatThrownBy { insertBuilding(snapshotId = "a".repeat(21) + "=") }.isInstanceOf(DataIntegrityViolationException::class.java)
	}

	@Test
	@DisplayName("manifest 를 지우면 참조 복제가 함께 지워진다")
	fun referencesCascade() {
		val id = insertBuilding()
		jdbc.sql("INSERT INTO dashboard_cache.snapshot_teams VALUES (:id, :team, '플랫폼', false)")
			.param("id", id).param("team", UUID.randomUUID()).update()
		jdbc.sql(
			"INSERT INTO dashboard_cache.snapshot_members VALUES (:id, :member, 'dev@example.test', NULL, 'owner', 'active', ARRAY[]::uuid[], now())",
		).param("id", id).param("member", UUID.randomUUID()).update()

		jdbc.sql("DELETE FROM dashboard_cache.snapshots WHERE snapshot_id = :id").param("id", id).update()

		for (table in listOf("snapshot_teams", "snapshot_members")) {
			assertThat(jdbc.sql("SELECT count(*) FROM dashboard_cache.$table WHERE snapshot_id = :id").param("id", id).query(Long::class.java).single())
				.describedAs(table).isZero()
		}
	}
}
