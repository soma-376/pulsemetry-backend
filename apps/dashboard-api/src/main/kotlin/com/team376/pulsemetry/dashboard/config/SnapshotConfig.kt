package com.team376.pulsemetry.dashboard.config

import com.team376.pulsemetry.dashboard.cache.ClickHouseCacheClient
import com.team376.pulsemetry.dashboard.snapshot.ModelResolution
import com.team376.pulsemetry.dashboard.snapshot.RetentionBoundaryReader
import com.team376.pulsemetry.dashboard.snapshot.SnapshotBuilder
import com.team376.pulsemetry.dashboard.snapshot.SnapshotCopySql
import com.team376.pulsemetry.dashboard.snapshot.SnapshotReferenceCopier
import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class SnapshotConfig {

	/** 시각은 UTC 시계 하나에서 얻는다. 테스트가 바꿔 끼운다. */
	@Bean
	fun clock(): Clock = Clock.systemUTC()

	/** 운영의 해석 규칙 판 — 검증된 공급자 범위가 없다(ADR 0023 §1 · ADR 0020 부록 C). */
	@Bean
	fun modelResolution(): ModelResolution = ModelResolution.NONE

	/** 원천 읽기는 주 DataSource 의 `JdbcClient`(읽기 전용 계정), 캐시 쓰기는 캐시 DataSource 로 만든 것이다. */
	@Bean
	fun snapshotBuilder(
		properties: DashboardApiProperties,
		source: JdbcClient,
		@Qualifier(CacheStoreConfig.CACHE_DATA_SOURCE) cacheDataSource: HikariDataSource,
		clickHouse: ClickHouseCacheClient,
		resolution: ModelResolution,
		clock: Clock,
	): SnapshotBuilder {
		val cache = JdbcClient.create(cacheDataSource)
		return SnapshotBuilder(
			boundaries = RetentionBoundaryReader(source),
			cache = cache,
			references = SnapshotReferenceCopier(source, cache),
			clickHouse = clickHouse,
			sql = SnapshotCopySql(properties.clickhouse.source.database, resolution),
			resolution = resolution,
			buildTimeout = properties.snapshot.buildTimeout,
			purgeGrace = properties.snapshot.purgeGrace,
			clock = clock,
		)
	}
}
