package com.team376.pulsemetry.dashboard.cache

import com.team376.pulsemetry.dashboard.store.ClickHouseConnection
import com.team376.pulsemetry.dashboard.support.DashboardTestStores
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

/**
 * ClickHouse `dashboard_cache` DDL (ADR 0023 §1). 기대값은 ADR 의 표와 문장에서 쓴다.
 * 이 클래스만의 DB 에 적용해 앱 컨텍스트가 적용한 캐시 DB 와 섞이지 않게 한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClickHouseCacheSchemaTest {

	private val database = "cache_schema_${System.nanoTime()}"
	private val client = ClickHouseCacheClient(
		ClickHouseConnection(DashboardTestStores.clickHouseUrl(), database, "default", "", Duration.ofSeconds(30), JsonMapper.builder().build()),
		Duration.ofSeconds(30),
	)

	@BeforeAll
	fun applyTwice() {
		DashboardTestStores.clickHouseAdmin("CREATE DATABASE $database")
		ClickHouseCacheSchema(client).apply()
		// 기동마다 전량 다시 돈다 — 두 번째도 성공해야 한다(ADR 0015).
		ClickHouseCacheSchema(client).apply()
	}

	private fun rows(sql: String): List<List<String>> =
		DashboardTestStores.clickHouseAdmin("$sql FORMAT TSVRaw").lineSequence().filter { it.isNotEmpty() }.map { it.split('\t') }.toList()

	@Test
	@DisplayName("테이블 넷과 입구·뷰 둘 — 복사본은 일반 MergeTree, 입구는 Null")
	fun enginesMatchTheDecision() {
		val engines = rows("SELECT name, engine FROM system.tables WHERE database = '$database' ORDER BY name").associate { it[0] to it[1] }

		assertThat(engines).isEqualTo(
			mapOf(
				"snapshot_intake" to "Null",
				"snapshot_intake_days" to "MaterializedView",
				"snapshot_intake_usage" to "MaterializedView",
				"snapshot_member_activity" to "MergeTree",
				"snapshot_observed_days" to "MergeTree",
				"snapshot_usage" to "MergeTree",
			),
		)
	}

	@Test
	@DisplayName("복사본 셋은 purge_after 로 파티션하고 TTL 로 부분 단위 정리한다")
	fun physicalCleanupIsTtl() {
		for (table in listOf("snapshot_usage", "snapshot_observed_days", "snapshot_member_activity")) {
			val (partition, sorting, engineFull) = rows(
				"SELECT partition_key, sorting_key, engine_full FROM system.tables WHERE database = '$database' AND name = '$table'",
			).single()

			assertThat(partition).describedAs(table).isEqualTo("toDate(purge_after)")
			assertThat(sorting).describedAs(table).startsWith("tenant_id, snapshot_id, build_id")
			assertThat(engineFull).describedAs(table).contains("TTL purge_after").contains("ttl_only_drop_parts = 1")
		}
	}

	@Test
	@DisplayName("snapshot_usage 의 열은 ADR 0023 §1 표와 같다")
	fun usageColumnsMatchTheTable() {
		val columns = rows("SELECT name, type FROM system.columns WHERE database = '$database' AND table = 'snapshot_usage' ORDER BY position")
			.map { it[0] to it[1] }

		assertThat(columns).containsExactly(
			"tenant_id" to "LowCardinality(String)",
			"snapshot_id" to "String",
			"build_id" to "String",
			"observation_id" to "FixedString(64)",
			"installation_id" to "String",
			"source_time" to "DateTime64(9, 'UTC')",
			"in_current" to "Bool",
			"in_previous" to "Bool",
			"product" to "LowCardinality(String)",
			"surface" to "LowCardinality(String)",
			"service_name" to "Nullable(String)",
			"product_version" to "Nullable(String)",
			"workload_kind" to "LowCardinality(String)",
			"usage_scope" to "LowCardinality(String)",
			"session_id" to "Nullable(String)",
			"session_id_namespace" to "Nullable(String)",
			"member_id" to "Nullable(String)",
			"team_id_as_of" to "Nullable(String)",
			"model" to "Nullable(String)",
			"provider" to "Nullable(String)",
			"model_id" to "String",
			"model_resolution_version" to "String",
			"tokens_input" to "Nullable(Int64)",
			"tokens_output" to "Nullable(Int64)",
			"tokens_cache_read" to "Nullable(Int64)",
			"tokens_cache_create" to "Nullable(Int64)",
			"tokens_input_uncached" to "Nullable(Int64)",
			"tokens_total_derived" to "Nullable(Int64)",
			"input_semantics" to "LowCardinality(String)",
			"output_semantics" to "LowCardinality(String)",
			"semantics_profile" to "Nullable(String)",
			"cost_reported_usd" to "Nullable(Decimal(38, 12))",
			"reported_cost_basis" to "LowCardinality(String)",
			"cost_estimated_usd" to "Nullable(Decimal(38, 12))",
			"pricing_version" to "Nullable(String)",
			"quality_flags" to "Array(LowCardinality(String))",
			"purge_after" to "DateTime('UTC')",
		)
	}

	@Test
	@DisplayName("입구의 열은 사용량 열 전부 + is_usage·origin·observed_date 다 — 뷰가 이름으로 옮긴다")
	fun intakeCarriesEveryUsageColumn() {
		fun names(table: String) =
			rows("SELECT name FROM system.columns WHERE database = '$database' AND table = '$table'").map { it[0] }.toSet()

		assertThat(names("snapshot_intake")).isEqualTo(names("snapshot_usage") + setOf("is_usage", "origin", "observed_date"))
	}

	@Test
	@DisplayName("입구 한 번의 INSERT 가 사용량 행과 관측 일자로 갈린다 — 일자는 사용량 아닌 관측까지 센다")
	fun oneInsertFansOutToUsageAndDays() {
		val snapshot = "snap_${System.nanoTime()}"
		DashboardTestStores.clickHouseAdmin(
			"""
			INSERT INTO $database.snapshot_intake
			    (tenant_id, snapshot_id, build_id, is_usage, origin, observed_date, observation_id, source_time,
			     in_current, in_previous, product, model_id, model_resolution_version, tokens_output, purge_after)
			VALUES
			    ('t1', '$snapshot', 'b1', true,  'telemetry_events',        '2026-09-24', repeat('a', 64), '2026-09-24 14:59:00', true, false, 'claude_code', 'm', 'v', 10, now() + 3600),
			    ('t1', '$snapshot', 'b1', true,  'telemetry_events',        '2026-09-24', repeat('b', 64), '2026-09-24 14:00:00', true, true,  'claude_code', 'm', 'v', NULL, now() + 3600),
			    ('t1', '$snapshot', 'b1', false, 'telemetry_events',        '2026-09-24', repeat('c', 64), '2026-09-24 10:00:00', false, false, 'unknown', '', '', NULL, now() + 3600),
			    ('t1', '$snapshot', 'b1', false, 'telemetry_metric_points', '2026-09-25', repeat('d', 64), '2026-09-25 10:00:00', false, false, 'claude_code', '', '', NULL, now() + 3600),
			    ('t1', '$snapshot', 'b1', false, 'telemetry_metric_points', '2026-09-25', repeat('e', 64), '2026-09-25 11:00:00', false, false, 'claude_code', '', '', NULL, now() + 3600)
			""".trimIndent(),
		)

		val usage = rows(
			"SELECT observation_id, in_current, in_previous, ifNull(toString(tokens_output), 'NULL') FROM $database.snapshot_usage " +
				"WHERE snapshot_id = '$snapshot' ORDER BY observation_id",
		)
		assertThat(usage).containsExactly(
			listOf("a".repeat(64), "true", "false", "10"),
			// 겹친 기간의 관측은 한 번 — 소속 표시 둘. 미보고 토큰은 null 그대로.
			listOf("b".repeat(64), "true", "true", "NULL"),
		)

		val days = rows(
			"SELECT observed_date, origin, sum(observations) FROM $database.snapshot_observed_days " +
				"WHERE snapshot_id = '$snapshot' GROUP BY observed_date, origin ORDER BY observed_date, origin",
		)
		assertThat(days).containsExactly(
			listOf("2026-09-24", "telemetry_events", "3"),
			listOf("2026-09-25", "telemetry_metric_points", "2"),
		)
	}
}
