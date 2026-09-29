package com.team376.pulsemetry.dashboard.config

import com.team376.pulsemetry.dashboard.analytics.AnalyticsFrames
import com.team376.pulsemetry.dashboard.analytics.ComparisonPolicy
import com.team376.pulsemetry.dashboard.analytics.CurrentStateTokens
import com.team376.pulsemetry.dashboard.analytics.IngestStatusReader
import com.team376.pulsemetry.dashboard.analytics.MembersService
import com.team376.pulsemetry.dashboard.analytics.OverviewService
import com.team376.pulsemetry.dashboard.analytics.SettingsService
import com.team376.pulsemetry.dashboard.analytics.SnapshotReferences
import com.team376.pulsemetry.dashboard.analytics.TeamDirectoryService
import com.team376.pulsemetry.dashboard.analytics.TeamsService
import com.team376.pulsemetry.dashboard.analytics.UsageAggregator
import com.team376.pulsemetry.dashboard.analytics.VendorUsageReader
import com.team376.pulsemetry.dashboard.snapshot.ModelResolution
import com.team376.pulsemetry.dashboard.snapshot.RetentionBoundaryReader
import com.team376.pulsemetry.dashboard.cache.ClickHouseCacheClient
import com.team376.pulsemetry.dashboard.request.PageCursorCodec
import com.team376.pulsemetry.dashboard.snapshot.SnapshotService
import com.team376.pulsemetry.dashboard.source.ClickHouseSourceReader
import com.team376.pulsemetry.persistence.telemetryops.TenantIngestSummaryStore
import com.team376.pulsemetry.persistence.telemetryops.TenantSummaryBackfill
import com.team376.pulsemetry.persistence.enrollment.management.VendorCatalog
import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/**
 * 조회 계산의 조립. payload 는 캐시 계정으로 읽고(자기 캐시), 수집 운영 현황은 원천 계정으로 읽는다.
 */
@Configuration(proxyBeanMethods = false)
class AnalyticsConfig {

	@Bean
	fun vendorCatalog(source: JdbcClient): VendorCatalog = VendorCatalog(source)

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
	fun analyticsFrames(
		snapshots: SnapshotService,
		references: SnapshotReferences,
		ingest: IngestStatusReader,
		comparison: ComparisonPolicy,
		clock: Clock,
	): AnalyticsFrames = AnalyticsFrames(snapshots, references, ingest, comparison, clock)

	@Bean
	fun teamsService(
		frames: AnalyticsFrames,
		aggregator: UsageAggregator,
		references: SnapshotReferences,
		snapshots: SnapshotService,
		codec: PageCursorCodec,
	): TeamsService = TeamsService(frames, aggregator, references, snapshots, codec)

	@Bean
	fun currentStateTokens(mapper: ObjectMapper): CurrentStateTokens = CurrentStateTokens(mapper)

	/** 현재 디렉터리는 원천 계정으로 읽는다. */
	@Bean
	fun teamDirectoryService(source: JdbcClient, codec: PageCursorCodec, tokens: CurrentStateTokens, clock: Clock): TeamDirectoryService =
		TeamDirectoryService(source, codec, tokens, clock)

	@Bean
	fun membersService(
		@org.springframework.beans.factory.annotation.Value("\${pulsemetry.management.enabled:false}") managementEnabled: Boolean,
		properties: DashboardApiProperties,
		frames: AnalyticsFrames,
		aggregator: UsageAggregator,
		references: SnapshotReferences,
		snapshots: SnapshotService,
		codec: PageCursorCodec,
		tokens: CurrentStateTokens,
		clock: Clock,
	): MembersService = MembersService(frames, aggregator, references, snapshots, codec, tokens, properties.members.idleDays, clock, managementEnabled)

	@Bean
	fun vendorUsageReader(reader: ClickHouseSourceReader, resolution: ModelResolution, boundaries: RetentionBoundaryReader): VendorUsageReader =
		VendorUsageReader(reader, resolution, boundaries)

	@Bean
	fun settingsService(
		@org.springframework.beans.factory.annotation.Value("\${pulsemetry.management.enabled:false}") managementEnabled: Boolean,
		properties: DashboardApiProperties,
		source: JdbcClient,
		vendorUsage: VendorUsageReader,
		frames: AnalyticsFrames,
		tokens: CurrentStateTokens,
		codec: PageCursorCodec,
		mapper: ObjectMapper,
		clock: Clock,
		catalog: VendorCatalog,
	): SettingsService = SettingsService(source, vendorUsage, frames, tokens, codec, mapper, properties.members.idleDays, clock, managementEnabled, catalog)

	@Bean
	fun overviewService(frames: AnalyticsFrames, aggregator: UsageAggregator, references: SnapshotReferences): OverviewService =
		OverviewService(frames, aggregator, references)
}
