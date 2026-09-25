package com.team376.pulsemetry.dashboard.support

import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID

/**
 * 테스트 JVM 하나에 한 벌만 뜨는 원천 저장소 둘. 앱은 설정 키(`pulsemetry.dashboard.*.source`)로만 연결하므로
 * `@ServiceConnection` 대신 [register] 가 그 키를 채운다.
 *
 * 앱의 RDS 연결은 **읽기 전용**이라 시드는 [writer] 로 따로 쓴다 — 운영의 권한 분리를 테스트도 그대로 따른다.
 */
object DashboardTestStores {

	private const val CLICKHOUSE_HTTP_PORT = 8123

	val postgres: PostgreSQLContainer by lazy {
		PostgreSQLContainer(PostgresContainerConfig.POSTGRES_IMAGE).also { it.start() }
	}

	/** 태그는 적재 모듈 테스트·infra 배포 이미지와 같다. */
	val clickhouse: GenericContainer<*> by lazy {
		GenericContainer("clickhouse/clickhouse-server:24.8-alpine")
			.withExposedPorts(CLICKHOUSE_HTTP_PORT)
			// 이게 없으면 entrypoint 가 default 유저를 루프백 전용으로 잠근다 (infra ADR-0019).
			.withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1")
			.waitingFor(Wait.forHttp("/ping").forPort(CLICKHOUSE_HTTP_PORT).forStatusCode(200))
			.also { it.start() }
	}

	fun clickHouseUrl(): String = "http://${clickhouse.host}:${clickhouse.getMappedPort(CLICKHOUSE_HTTP_PORT)}"

	/** 시드·정리용 쓰기 연결. 앱의 연결과 다르다. */
	val writer: JdbcClient by lazy {
		JdbcClient.create(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
	}

	fun register(registry: DynamicPropertyRegistry) {
		registry.add("pulsemetry.dashboard.rds.source.url") { postgres.jdbcUrl }
		registry.add("pulsemetry.dashboard.rds.source.username") { postgres.username }
		registry.add("pulsemetry.dashboard.rds.source.password") { postgres.password }

		registry.add("pulsemetry.dashboard.clickhouse.source.url") { clickHouseUrl() }
		registry.add("pulsemetry.dashboard.clickhouse.source.database") { "default" }
		registry.add("pulsemetry.dashboard.clickhouse.source.username") { "default" }
		registry.add("pulsemetry.dashboard.clickhouse.source.password") { "" }
		registry.add("pulsemetry.dashboard.clickhouse.source.query-timeout") { "10s" }
		registry.add("pulsemetry.dashboard.clickhouse.source.max-result-rows") { "100000" }
		registry.add("pulsemetry.dashboard.clickhouse.source.max-result-bytes") { "16777216" }

		// 운영의 앱은 Flyway 를 끈다(enrollment-api 가 소유). 테스트는 격리된 컨테이너라 스키마를 만들 주체가 없으므로
		// 여기서만 켠다 — 앱 연결이 읽기 전용이므로 Flyway 에는 자기 연결(spring.flyway.url)을 준다.
		registry.add("spring.flyway.enabled") { "true" }
		registry.add("spring.flyway.url") { postgres.jdbcUrl }
		registry.add("spring.flyway.user") { postgres.username }
		registry.add("spring.flyway.password") { postgres.password }
		registry.add("spring.flyway.locations") { "classpath:db/migration" }
		registry.add("spring.flyway.schemas") { "enrollment" }
		registry.add("spring.flyway.default-schema") { "enrollment" }
	}

	/** 조직 하나를 넣는다. [deleted] 면 삭제 표시를 단다. */
	fun insertTenant(id: UUID = UUID.randomUUID(), name: String = "테스트 조직", deleted: Boolean = false): UUID {
		writer.sql(
			"""
			INSERT INTO enrollment.tenants (id, name, timezone, status, created_at, updated_at, deleted_at)
			VALUES (:id, :name, 'Asia/Seoul', 'active', now(), now(), CASE WHEN :deleted THEN now() END)
			""".trimIndent(),
		)
			.param("id", id)
			.param("name", name)
			.param("deleted", deleted)
			.update()
		return id
	}
}
