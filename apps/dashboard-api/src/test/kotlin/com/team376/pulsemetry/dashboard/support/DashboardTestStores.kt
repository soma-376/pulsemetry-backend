package com.team376.pulsemetry.dashboard.support

import com.team376.pulsemetry.dashboard.cache.ClickHouseCacheClient
import com.team376.pulsemetry.dashboard.cache.ClickHouseCacheSchema
import com.team376.pulsemetry.dashboard.cache.RdsCacheSchema
import com.team376.pulsemetry.dashboard.store.ClickHouseConnection
import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
import com.team376.pulsemetry.persistence.telemetry.ClickHouseHttpClient
import com.team376.pulsemetry.persistence.telemetry.ClickHouseSchemaMigrator
import com.team376.pulsemetry.persistence.telemetryops.TelemetryOpsSchemaMigrator
import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import tools.jackson.databind.json.JsonMapper

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

	/** 캐시 DB. 운영에서는 infra 가 만든다(ADR 0023 §1) — 테스트는 컨테이너를 띄울 때 만든다. */
	const val CACHE_DATABASE = "dashboard_cache"

	/** 태그는 적재 모듈 테스트·infra 배포 이미지와 같다. */
	val clickhouse: GenericContainer<*> by lazy {
		GenericContainer("clickhouse/clickhouse-server:24.8-alpine")
			.withExposedPorts(CLICKHOUSE_HTTP_PORT)
			// 이게 없으면 entrypoint 가 default 유저를 루프백 전용으로 잠근다 (infra ADR-0019).
			.withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1")
			.waitingFor(Wait.forHttp("/ping").forPort(CLICKHOUSE_HTTP_PORT).forStatusCode(200))
			.also {
				it.start()
				clickHouseAdmin(clickHouseUrl(it), "CREATE DATABASE IF NOT EXISTS $CACHE_DATABASE")
			}
	}

	fun clickHouseUrl(): String = clickHouseUrl(clickhouse)

	private fun clickHouseUrl(container: GenericContainer<*>): String =
		"http://${container.host}:${container.getMappedPort(CLICKHOUSE_HTTP_PORT)}"

	/** 앱 경로를 거치지 않는 관리 문장(시드·DB 생성). 결과 본문을 돌려준다. */
	fun clickHouseAdmin(sql: String): String = clickHouseAdmin(clickHouseUrl(), sql)

	private fun clickHouseAdmin(url: String, sql: String): String {
		val response = HttpClient.newHttpClient().send(
			HttpRequest.newBuilder(URI.create("$url/")).POST(HttpRequest.BodyPublishers.ofString(sql)).build(),
			HttpResponse.BodyHandlers.ofString(),
		)
		check(response.statusCode() == 200) { "clickhouse ${response.statusCode()}: ${response.body()}" }
		return response.body()
	}

	/** 시드·정리용 쓰기 연결. 앱의 연결과 다르다. */
	val writer: JdbcClient by lazy {
		JdbcClient.create(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
	}

	fun register(registry: DynamicPropertyRegistry) {
		// 운영에서 다른 앱이 적용하는 원천 스키마(telemetry_ops 는 enrollment-api, 분석 테이블은 ingest)를 컨텍스트보다 먼저 세운다.
		ensureSchemas()
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

		registry.add("pulsemetry.dashboard.clickhouse.cache.url") { clickHouseUrl() }
		registry.add("pulsemetry.dashboard.clickhouse.cache.database") { CACHE_DATABASE }
		registry.add("pulsemetry.dashboard.clickhouse.cache.username") { "default" }
		registry.add("pulsemetry.dashboard.clickhouse.cache.password") { "" }
		registry.add("pulsemetry.dashboard.clickhouse.cache.query-timeout") { "30s" }

		// 캐시 계정은 쓰기 권한이 있다 — 테스트는 컨테이너의 관리 계정을 쓴다.
		registry.add("pulsemetry.dashboard.rds.cache.url") { postgres.jdbcUrl }
		registry.add("pulsemetry.dashboard.rds.cache.username") { postgres.username }
		registry.add("pulsemetry.dashboard.rds.cache.password") { postgres.password }

		registry.add("pulsemetry.dashboard.snapshot.build-timeout") { "60s" }
		registry.add("pulsemetry.dashboard.snapshot.purge-grace") { "60s" }
		registry.add("pulsemetry.dashboard.snapshot.max-concurrent-builds") { "4" }
		registry.add("pulsemetry.dashboard.snapshot.max-copy-rows") { "1000000" }
		registry.add("pulsemetry.dashboard.snapshot.max-copy-bytes") { "1000000000" }
		// 테스트 중에는 정리 작업이 돌지 않게 길게 둔다 — 정리는 SnapshotLifecycleTest 가 직접 부른다.
		registry.add("pulsemetry.dashboard.snapshot.cleanup-interval") { "1h" }
		registry.add("pulsemetry.dashboard.members.idle-days") { "14" }

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

	private val schemas: Unit by lazy {
		val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
		Flyway.configure().dataSource(dataSource).schemas("enrollment").defaultSchema("enrollment")
			.locations("classpath:db/migration").load().migrate()
		TelemetryOpsSchemaMigrator(dataSource).migrate()
		RdsCacheSchema(dataSource).migrate()
		ClickHouseSchemaMigrator(ClickHouseHttpClient(clickHouseUrl())).apply()
		ClickHouseCacheSchema(
			ClickHouseCacheClient(
				ClickHouseConnection(clickHouseUrl(), CACHE_DATABASE, "default", "", Duration.ofSeconds(30), JsonMapper.builder().build()),
				Duration.ofSeconds(30),
			),
		).apply()
	}

	/**
	 * 원천·캐시 스키마를 전부 세운다 — enrollment(Flyway), telemetry_ops, 분석 테이블(`default`), 두 캐시 스키마. 앱 컨텍스트 없이 도는 테스트가
	 * 부른다. 모두 멱등이라 앱 기동의 적용과 겹쳐도 된다.
	 */
	fun ensureSchemas() = schemas

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
