package com.team376.pulsemetry.retention

import com.team376.pulsemetry.RetentionWorkerApplication
import com.team376.pulsemetry.persistence.telemetryops.TenantRetentionBoundary
import com.team376.pulsemetry.persistence.telemetryops.TenantRetentionBoundaryStore
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import java.time.Instant
import java.util.UUID

/**
 * 실행 단위 전체 — 설정을 인자로 받아 명령 하나를 실행하고 종료 코드로 끝나는지(ADR 0024 §5). 종료 코드의 뜻은 [RetentionCommandRunner] 의 표다.
 */
class RetentionWorkerApplicationTest {

	private val stores = RetentionTestStores
	private val tenant: UUID = UUID.randomUUID()

	@BeforeEach
	fun setUp() {
		stores.clear()
	}

	/** 운영에서는 환경변수가 채우는 설정 — 명령행 인자가 application.yaml 의 빈 기본값보다 앞선다. */
	private fun settings(): Array<String> = arrayOf(
		"--spring.datasource.url=${stores.postgres.jdbcUrl}",
		"--spring.datasource.username=${stores.postgres.username}",
		"--spring.datasource.password=${stores.postgres.password}",
		"--pulsemetry.retention.clickhouse.url=${stores.clickHouseUrl}",
		"--pulsemetry.retention.clickhouse.database=default",
		"--pulsemetry.retention.clickhouse.timeout=30s",
		"--pulsemetry.retention.drain.timeout=10s",
		"--pulsemetry.retention.drain.poll-interval=50ms",
		"--pulsemetry.retention.max-passes=3",
	)

	private fun run(vararg args: String): Int = start(*args).let { SpringApplication.exit(it) }

	private fun start(vararg args: String): ConfigurableApplicationContext {
		stores.client // ClickHouse 스키마를 먼저 세운다(운영에서는 ingest 의 몫).
		return SpringApplicationBuilder(RetentionWorkerApplication::class.java).run(*args)
	}

	@Test
	@DisplayName("명령을 실행하고 논리 삭제 완료면 0 으로 끝난다 — 경계가 발효됐다")
	fun aCompletedRunExitsWithZero() {
		stores.insert("telemetry_events", listOf(RetentionTestStores.Row(tenant, RetentionTestStores.observationId(1), Instant.parse("2025-01-01T00:00:00Z"))))

		val code = run(*settings(), "--tenant=$tenant", "--retention-months=12", "--as-of=2026-09-24T10:00:00+09:00")

		assertThat(code).isEqualTo(RetentionCommandRunner.EXIT_DELETED)
		assertThat(stores.rows("telemetry_events", tenant)).isZero()
		assertThat(TenantRetentionBoundaryStore(stores.dataSource).read(tenant))
			.isEqualTo(TenantRetentionBoundary(tenant, Instant.parse("2025-09-23T15:00:00Z"), 1))
	}

	@Test
	@DisplayName("DDL 을 실행하지 않고 enrollment 를 읽지 않는다 — Flyway 이력도 JPA 도 없다")
	fun appliesNoSchemaAndSkipsJpa() {
		stores.dataSource.connection.use { c -> c.createStatement().use { it.execute("DROP TABLE IF EXISTS public.flyway_schema_history") } }

		val context = start(*settings(), "--tenant=$tenant", "--retention-months=12", "--as-of=2026-09-24T10:00:00+09:00")
		val hasEntityManager = context.containsBean("entityManagerFactory")
		assertThat(SpringApplication.exit(context)).isEqualTo(RetentionCommandRunner.EXIT_DELETED)

		assertThat(hasEntityManager).isFalse()
		val histories = stores.dataSource.connection.use { c ->
			c.createStatement().use { st ->
				st.executeQuery("SELECT table_schema FROM information_schema.tables WHERE table_name = 'flyway_schema_history'").use { rs ->
					generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
				}
			}
		}
		assertThat(histories).containsExactly("telemetry_ops")
	}

	@Test
	@DisplayName("인자가 틀리면 아무것도 하지 않고 2 로 끝난다")
	fun aBadCommandExitsWithTwo() {
		val code = run(*settings(), "--tenant=$tenant", "--retention-months=12")

		assertThat(code).isEqualTo(RetentionCommandRunner.EXIT_USAGE)
		assertThat(TenantRetentionBoundaryStore(stores.dataSource).read(tenant)).isEqualTo(TenantRetentionBoundary.none(tenant))
	}

	@Test
	@DisplayName("필수 설정이 비면 기동하지 않는다 — 기본값으로 다른 저장소를 지우지 않는다")
	fun missingSettingsFailStartup() {
		val withoutDrain = settings().filterNot { it.startsWith("--pulsemetry.retention.drain.timeout") }.toTypedArray()

		assertThatThrownBy { run(*withoutDrain, "--tenant=$tenant", "--retention-months=12", "--as-of=2026-09-24T10:00:00+09:00") }
			.isInstanceOf(Exception::class.java)
		assertThat(TenantRetentionBoundaryStore(stores.dataSource).read(tenant)).isEqualTo(TenantRetentionBoundary.none(tenant))
	}
}
