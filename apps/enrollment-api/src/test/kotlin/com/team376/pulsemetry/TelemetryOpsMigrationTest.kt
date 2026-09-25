package com.team376.pulsemetry

import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
import com.team376.pulsemetry.persistence.telemetryops.TelemetryOpsSchemaMigrator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * 이 앱의 기동이 `telemetry_ops` 를 `enrollment` 와 섞지 않고 마이그레이션하는지 본다 (ADR 0021 §3).
 *
 * [PulsemetryApplicationTests] 와 애노테이션이 같아 컨텍스트 캐시를 공유한다 — 컨테이너가 하나 더 뜨지 않는다.
 */
@SpringBootTest
@Import(PostgresContainerConfig::class)
class TelemetryOpsMigrationTest {

	@Autowired
	private lateinit var jdbcClient: JdbcClient

	@Autowired
	private lateinit var migrator: TelemetryOpsSchemaMigrator

	@Test
	@DisplayName("기동하면 telemetry_ops 의 세 테이블과 그 스키마 안의 이력 테이블이 생긴다")
	fun startupCreatesTheOperationsSchema() {
		val tables = strings(
			"SELECT table_name FROM information_schema.tables WHERE table_schema = 'telemetry_ops' AND table_type = 'BASE TABLE'",
		)

		assertThat(tables).containsExactlyInAnyOrder(
			"flyway_schema_history",
			"tenant_ingest_summary",
			"tenant_summary_backfill",
			"tenant_retention_boundary",
		)
		assertThat(strings("SELECT script FROM telemetry_ops.flyway_schema_history WHERE success AND version IS NOT NULL"))
			.containsExactly("V1__telemetry_ops_schema.sql")
	}

	@Test
	@DisplayName("enrollment 이력에는 telemetry_ops 의 스크립트가 없고, 다시 실행해도 enrollment 이력이 변하지 않는다")
	fun enrollmentHistoryStaysSeparateAndUntouched() {
		assertThat(strings("SELECT script FROM enrollment.flyway_schema_history"))
			.noneMatch { it.contains("telemetry_ops") }
		val before = strings("SELECT installed_rank || ':' || coalesce(version, '') || ':' || script FROM enrollment.flyway_schema_history ORDER BY installed_rank")

		assertThat(migrator.migrate()).isZero()

		val after = strings("SELECT installed_rank || ':' || coalesce(version, '') || ':' || script FROM enrollment.flyway_schema_history ORDER BY installed_rank")
		assertThat(after).isEqualTo(before)
		// enrollment 마이그레이션도 여전히 돈다 — 마이그레이터가 Flyway 빈이었다면 자동설정이 물러나 비었을 것이다.
		assertThat(strings("SELECT version FROM enrollment.flyway_schema_history WHERE version = '1' AND success"))
			.containsExactly("1")
	}

	private fun strings(sql: String): List<String> =
		jdbcClient.sql(sql).query(String::class.java).list().map { it.orEmpty() }
}
