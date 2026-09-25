package com.team376.pulsemetry.dashboard.config

import com.team376.pulsemetry.dashboard.source.ClickHouseSourceReader
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import tools.jackson.databind.ObjectMapper

/**
 * 원천 읽기 연결 둘 (ADR 0022 §4). 계정은 `pulsemetry.dashboard.{rds,clickhouse}.source` 에서만 온다 —
 * Boot 의 `spring.datasource` 는 쓰지 않는다. 캐시 쓰기 연결은 따로 선다.
 */
@Configuration(proxyBeanMethods = false)
class SourceStoreConfig {

	/**
	 * RDS `enrollment`·`telemetry_ops` 읽기. **주(`@Primary`) DataSource 다** — JPA·`JdbcClient` 가 이것을 쓴다.
	 * 캐시 쓰기 DataSource 가 더해져도 엔티티 조회가 쓰기 계정으로 옮겨 가지 않게 한다.
	 *
	 * 커넥션을 읽기 전용으로 연다. 강제는 계정 권한이 하고(SELECT 만) 이것은 2차 방어다. PostgreSQL 드라이버의
	 * `readOnlyMode` 기본값(`transaction`)은 자동 커밋 문장에 읽기 전용을 걸지 않으므로 `always` 로 둔다.
	 */
	@Bean
	@Primary
	fun sourceDataSource(properties: DashboardApiProperties): HikariDataSource {
		val source = properties.rds.source
		return HikariDataSource(
			HikariConfig().apply {
				poolName = "dashboard-rds-source"
				jdbcUrl = source.url
				username = source.username
				password = source.password
				connectionTimeout = source.connectionTimeout.toMillis()
				isReadOnly = true
				addDataSourceProperty("readOnlyMode", "always")
			},
		)
	}

	@Bean
	fun clickHouseSourceReader(properties: DashboardApiProperties, mapper: ObjectMapper): ClickHouseSourceReader {
		val source = properties.clickhouse.source
		return ClickHouseSourceReader(
			baseUrl = source.url,
			database = source.database,
			username = source.username,
			password = source.password,
			queryTimeout = source.queryTimeout,
			maxResultRows = source.maxResultRows,
			maxResultBytes = source.maxResultBytes,
			mapper = mapper,
		)
	}
}
