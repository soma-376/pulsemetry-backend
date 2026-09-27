package com.team376.pulsemetry.persistence.telemetryops

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.SQLException
import java.util.UUID

/**
 * `telemetry_ops` 마이그레이션을 실제 PostgreSQL 위에서 고정한다 (ADR 0021 §2·§3).
 *
 * 컨테이너 하나를 클래스 전체가 쓴다 — 첫 적용과 재적용이 같은 DB 위의 순서라 [Order] 로 묶는다.
 * `enrollment` 마이그레이션과 한 DB 에서 공존하는지는 그것을 조립하는 `:apps:enrollment-api` 가 본다.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TelemetryOpsSchemaMigratorTest {

	private val dataSource = PGSimpleDataSource().apply {
		setURL(postgres.jdbcUrl)
		user = postgres.username
		password = postgres.password
	}

	@Test
	@Order(1)
	@DisplayName("빈 DB 에 V1·V2 를 적용하고, 이력은 telemetry_ops 안의 자기 테이블에 남는다")
	fun firstMigrationCreatesTheSchemaAndItsOwnHistory() {
		assertThat(TelemetryOpsSchemaMigrator(dataSource).migrate()).isEqualTo(2)

		assertThat(strings("SELECT table_name FROM information_schema.tables WHERE table_schema = 'telemetry_ops' ORDER BY 1"))
			.containsExactly(
				"flyway_schema_history",
				"retention_operations",
				"tenant_ingest_summary",
				"tenant_retention_boundary",
				"tenant_summary_backfill",
			)
		assertThat(strings("SELECT version FROM telemetry_ops.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank"))
			.containsExactly("1", "2")
		// 다른 스키마에 이력을 만들지 않는다 — enrollment 이력과 섞이지 않는 전제다.
		assertThat(strings("SELECT table_schema FROM information_schema.tables WHERE table_name = 'flyway_schema_history'"))
			.containsExactly("telemetry_ops")
	}

	@Test
	@Order(2)
	@DisplayName("다시 실행해도 아무것도 적용하지 않는다 — 기동마다 불려도 안전하다")
	fun migratingAgainIsANoOp() {
		assertThat(TelemetryOpsSchemaMigrator(dataSource).migrate()).isZero()
		assertThat(TelemetryOpsSchemaMigrator(dataSource).migrate()).isZero()
	}

	@Test
	@Order(3)
	@DisplayName("요약의 시각 컬럼은 NULL 을 허용하고 has_pre_ledger_history 는 false 로 시작한다")
	fun summaryColumnsKeepNullMeaning() {
		val tenant = UUID.randomUUID()
		execute("INSERT INTO telemetry_ops.tenant_ingest_summary (tenant_id) VALUES ('$tenant')")

		assertThat(
			strings(
				"SELECT concat_ws(',', coalesce(first_received_at::text, 'null'), coalesce(first_observed_at::text, 'null'), " +
					"coalesce(last_received_at::text, 'null'), has_pre_ledger_history::text) " +
					"FROM telemetry_ops.tenant_ingest_summary WHERE tenant_id = '$tenant'",
			),
		).containsExactly("null,null,null,false")
	}

	@Test
	@Order(4)
	@DisplayName("최초 수신이 마지막 수신보다 늦을 수 없다")
	fun firstReceivedCannotFollowLastReceived() {
		assertThatThrownBy {
			execute(
				"INSERT INTO telemetry_ops.tenant_ingest_summary (tenant_id, first_received_at, last_received_at) " +
					"VALUES ('${UUID.randomUUID()}', '2026-09-25T00:00:01Z', '2026-09-25T00:00:00Z')",
			)
		}.isInstanceOf(SQLException::class.java).hasMessageContaining("ck_tenant_ingest_summary_received_order")
	}

	@Test
	@Order(5)
	@DisplayName("enrollment 에 외래 키를 걸지 않는다 — tenant 삭제 절차가 따로 정리한다")
	fun noForeignKeysLeaveTheSchema() {
		assertThat(
			strings(
				"SELECT constraint_name FROM information_schema.table_constraints " +
					"WHERE table_schema = 'telemetry_ops' AND constraint_type = 'FOREIGN KEY'",
			),
		).isEmpty()
	}

	private fun execute(sql: String) {
		dataSource.connection.use { connection -> connection.createStatement().use { it.execute(sql) } }
	}

	private fun strings(sql: String): List<String> =
		dataSource.connection.use { connection ->
			connection.createStatement().use { statement ->
				statement.executeQuery(sql).use { rows ->
					buildList { while (rows.next()) add(rows.getString(1)) }
				}
			}
		}

	companion object {
		/** 배포 대상과 같은 메이저다 — `PostgresContainerConfig.POSTGRES_IMAGE` 와 같은 값이다. */
		@Container
		@JvmStatic
		val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:16-alpine")
	}
}
