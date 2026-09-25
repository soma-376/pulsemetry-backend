package com.team376.pulsemetry.dashboard.config

import com.team376.pulsemetry.dashboard.cache.ClickHouseCacheClient
import com.team376.pulsemetry.dashboard.snapshot.ModelResolution
import com.team376.pulsemetry.dashboard.snapshot.RetentionBoundaryReader
import com.team376.pulsemetry.dashboard.snapshot.SnapshotBuilder
import com.team376.pulsemetry.dashboard.snapshot.SnapshotCleaner
import com.team376.pulsemetry.dashboard.snapshot.SnapshotCleanupJob
import com.team376.pulsemetry.dashboard.snapshot.SnapshotCopySql
import com.team376.pulsemetry.dashboard.snapshot.SnapshotManifestStore
import com.team376.pulsemetry.dashboard.snapshot.SnapshotReferenceCopier
import com.team376.pulsemetry.dashboard.snapshot.SnapshotService
import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

/**
 * snapshot 의 조립 (ADR 0023). 원천 읽기는 주 DataSource 의 `JdbcClient`(읽기 전용 계정), 캐시 쓰기는 캐시 DataSource 로 만든 것이다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SnapshotConfig {

	/** 시각은 UTC 시계 하나에서 얻는다. 테스트가 바꿔 끼운다. */
	@Bean
	fun clock(): Clock = Clock.systemUTC()

	/** 운영의 해석 규칙 판 — 검증된 공급자 범위가 없다(ADR 0023 §1 · ADR 0020 부록 C). */
	@Bean
	fun modelResolution(): ModelResolution = ModelResolution.NONE

	@Bean
	fun snapshotLimits(properties: DashboardApiProperties): SnapshotBuilder.Limits = with(properties.snapshot) {
		SnapshotBuilder.Limits(buildTimeout, purgeGrace, maxConcurrentBuilds, maxCopyRows, maxCopyBytes)
	}

	@Bean
	fun snapshotManifestStore(@Qualifier(CacheStoreConfig.CACHE_DATA_SOURCE) cacheDataSource: HikariDataSource): SnapshotManifestStore =
		SnapshotManifestStore(JdbcClient.create(cacheDataSource), TransactionTemplate(DataSourceTransactionManager(cacheDataSource)))

	@Bean
	fun retentionBoundaryReader(source: JdbcClient): RetentionBoundaryReader = RetentionBoundaryReader(source)

	@Bean
	fun snapshotBuilder(
		properties: DashboardApiProperties,
		source: JdbcClient,
		@Qualifier(CacheStoreConfig.CACHE_DATA_SOURCE) cacheDataSource: HikariDataSource,
		boundaries: RetentionBoundaryReader,
		manifests: SnapshotManifestStore,
		clickHouse: ClickHouseCacheClient,
		resolution: ModelResolution,
		limits: SnapshotBuilder.Limits,
		clock: Clock,
	): SnapshotBuilder = SnapshotBuilder(
		boundaries = boundaries,
		manifests = manifests,
		references = SnapshotReferenceCopier(source, JdbcClient.create(cacheDataSource)),
		clickHouse = clickHouse,
		sql = SnapshotCopySql(properties.clickhouse.source.database, resolution),
		resolution = resolution,
		limits = limits,
		clock = clock,
	)

	@Bean
	fun snapshotService(
		builder: SnapshotBuilder,
		manifests: SnapshotManifestStore,
		boundaries: RetentionBoundaryReader,
		clock: Clock,
	): SnapshotService = SnapshotService(builder, manifests, boundaries, clock)

	@Bean
	fun snapshotCleaner(
		manifests: SnapshotManifestStore,
		clickHouse: ClickHouseCacheClient,
		limits: SnapshotBuilder.Limits,
		clock: Clock,
	): SnapshotCleaner = SnapshotCleaner(manifests, clickHouse, limits, clock)

	@Bean
	fun snapshotCleanupJob(cleaner: SnapshotCleaner): SnapshotCleanupJob = SnapshotCleanupJob(cleaner)
}
