package com.team376.pulsemetry.dashboard.config

import com.team376.pulsemetry.dashboard.source.ClickHouseSourceReader
import com.team376.pulsemetry.dashboard.store.ClickHouseConnection
import com.zaxxer.hikari.HikariDataSource
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.tracing.Tracer
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import tools.jackson.databind.ObjectMapper

/**
 * 원천 읽기 연결 둘 (ADR 0022 §4). 계정은 `pulsemetry.dashboard.{rds,clickhouse}.source` 에서만 온다 —
 * Boot 의 `spring.datasource` 는 쓰지 않는다. 캐시 연결은 `CacheStoreConfig` 가 세운다.
 */
@Configuration(proxyBeanMethods = false)
class SourceStoreConfig {

	/**
	 * RDS `enrollment`·`telemetry_ops` 읽기. **주(`@Primary`) DataSource 다** — JPA·`JdbcClient` 가 이것을 쓴다.
	 * 캐시 DataSource 가 따로 있어도 엔티티 조회가 캐시 계정으로 옮겨 가지 않게 한다.
	 *
	 * 커넥션을 읽기 전용으로 연다. 강제는 계정 권한이 하고(SELECT 만) 이것은 2차 방어다.
	 */
	@Bean
	@Primary
	fun sourceDataSource(properties: DashboardApiProperties, registry: ObjectProvider<MeterRegistry>): HikariDataSource =
		StoreDataSources.hikari("dashboard-rds-source", properties.rds.source, readOnly = true, registry.ifAvailable)

	@Bean
	fun clickHouseSourceReader(properties: DashboardApiProperties, mapper: ObjectMapper, tracer: ObjectProvider<Tracer>): ClickHouseSourceReader {
		val source = properties.clickhouse.source
		return ClickHouseSourceReader(
			connection = ClickHouseConnection(
				source.url, source.database, source.username, source.password, source.queryTimeout, mapper,
				tracer = tracer.getIfAvailable { Tracer.NOOP },
			),
			queryTimeout = source.queryTimeout,
			maxResultRows = source.maxResultRows,
			maxResultBytes = source.maxResultBytes,
		)
	}
}
