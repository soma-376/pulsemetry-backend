package com.team376.pulsemetry.dashboard.config

import com.team376.pulsemetry.dashboard.cache.CacheSchemaInitializer
import com.team376.pulsemetry.dashboard.cache.ClickHouseCacheClient
import com.team376.pulsemetry.dashboard.cache.ClickHouseCacheSchema
import com.team376.pulsemetry.dashboard.cache.RdsCacheSchema
import com.team376.pulsemetry.dashboard.store.ClickHouseConnection
import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

/**
 * 캐시 연결 둘과 기동 때의 스키마 적용 (ADR 0023 §3). 계정은 `pulsemetry.dashboard.{rds,clickhouse}.cache` 에서만 온다.
 */
@Configuration(proxyBeanMethods = false)
class CacheStoreConfig {

	/** RDS `dashboard_cache`. **주 DataSource 가 아니다** — 쓰는 코드가 이 이름으로 받는다. */
	@Bean(CACHE_DATA_SOURCE)
	fun cacheDataSource(properties: DashboardApiProperties): HikariDataSource =
		StoreDataSources.hikari("dashboard-rds-cache", properties.rds.cache, readOnly = false)

	@Bean
	fun clickHouseCacheClient(properties: DashboardApiProperties, mapper: ObjectMapper): ClickHouseCacheClient {
		val cache = properties.clickhouse.cache
		return ClickHouseCacheClient(
			connection = ClickHouseConnection(cache.url, cache.database, cache.username, cache.password, cache.queryTimeout, mapper),
			queryTimeout = cache.queryTimeout,
		)
	}

	@Bean
	fun cacheSchemaInitializer(
		@Qualifier(CACHE_DATA_SOURCE) cacheDataSource: HikariDataSource,
		client: ClickHouseCacheClient,
	): CacheSchemaInitializer = CacheSchemaInitializer(RdsCacheSchema(cacheDataSource), ClickHouseCacheSchema(client))

	companion object {
		const val CACHE_DATA_SOURCE = "cacheDataSource"
	}
}
