package com.team376.pulsemetry.dashboard.config

import com.team376.pulsemetry.dashboard.analytics.ComparisonPolicy
import com.team376.pulsemetry.dashboard.analytics.IngestStatusReader
import com.team376.pulsemetry.dashboard.analytics.OverviewService
import com.team376.pulsemetry.dashboard.analytics.SnapshotReferences
import com.team376.pulsemetry.dashboard.analytics.UsageAggregator
import com.team376.pulsemetry.dashboard.cache.ClickHouseCacheClient
import com.team376.pulsemetry.dashboard.snapshot.SnapshotService
import com.team376.pulsemetry.dashboard.source.ClickHouseSourceReader
import com.team376.pulsemetry.persistence.telemetryops.TenantIngestSummaryStore
import com.team376.pulsemetry.persistence.telemetryops.TenantSummaryBackfill
import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock

/**
 * 조회 계산의 조립. payload 는 캐시 계정으로 읽고(자기 캐시), 수집 운영 현황은 원천 계정으로 읽는다.
 */
@Configuration(proxyBeanMethods = false)
class AnalyticsConfig {

	@Bean
	fun usageAggregator(clickHouse: ClickHouseCacheClient): UsageAggregator = UsageAggregator(clickHouse)

	@Bean
	fun snapshotReferences(
		clickHouse: ClickHouseCacheClient,
		@Qualifier(CacheStoreConfig.CACHE_DATA_SOURCE) cacheDataSource: HikariDataSource,
	): SnapshotReferences = SnapshotReferences(clickHouse, JdbcClient.create(cacheDataSource))

	/** 요약·백필 기록은 읽기만 한다 — 원천 주 DataSource(읽기 전용 계정). */
	@Bean
	fun ingestStatusReader(sourceDataSource: HikariDataSource, ledger: ClickHouseSourceReader, source: JdbcClient): IngestStatusReader =
		IngestStatusReader(TenantIngestSummaryStore(sourceDataSource), TenantSummaryBackfill(sourceDataSource), ledger, source)

	/** 두 기간 모두 완전 관측일 때만 비교를 공개한다 — v1 에는 그 근거가 없다. */
	@Bean
	fun comparisonPolicy(): ComparisonPolicy = ComparisonPolicy.COMPLETE_ONLY

	@Bean
	fun overviewService(
		snapshots: SnapshotService,
		aggregator: UsageAggregator,
		references: SnapshotReferences,
		ingest: IngestStatusReader,
		comparison: ComparisonPolicy,
		clock: Clock,
	): OverviewService = OverviewService(snapshots, aggregator, references, ingest, comparison, clock)
}
