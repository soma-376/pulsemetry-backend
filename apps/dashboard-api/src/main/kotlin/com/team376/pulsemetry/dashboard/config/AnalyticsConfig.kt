package com.team376.pulsemetry.dashboard.config

import com.team376.pulsemetry.dashboard.analytics.AnalyticsFrames
import com.team376.pulsemetry.dashboard.analytics.ComparisonPolicy
import com.team376.pulsemetry.dashboard.analytics.OrganizationPolicies
import com.team376.pulsemetry.dashboard.analytics.CurrentStateTokens
import com.team376.pulsemetry.dashboard.analytics.IngestStatusReader
import com.team376.pulsemetry.dashboard.analytics.IngestThresholds
import com.team376.pulsemetry.dashboard.analytics.MembersService
import com.team376.pulsemetry.dashboard.analytics.SeatLedgerReader
import com.team376.pulsemetry.dashboard.analytics.SeatService
import com.team376.pulsemetry.dashboard.analytics.OverviewService
import com.team376.pulsemetry.dashboard.analytics.SettingsService
import com.team376.pulsemetry.dashboard.analytics.SnapshotReferences
import com.team376.pulsemetry.dashboard.analytics.TeamDirectoryService
import com.team376.pulsemetry.dashboard.analytics.TeamsService
import com.team376.pulsemetry.dashboard.analytics.UsageAggregator
import com.team376.pulsemetry.dashboard.analytics.VendorObservations
import com.team376.pulsemetry.dashboard.snapshot.SnapshotCompleteness
import com.team376.pulsemetry.dashboard.snapshot.ModelResolution
import com.team376.pulsemetry.dashboard.snapshot.RetentionBoundaryReader
import com.team376.pulsemetry.dashboard.cache.ClickHouseCacheClient
import com.team376.pulsemetry.dashboard.request.PageCursorCodec
import com.team376.pulsemetry.dashboard.snapshot.SnapshotService
import com.team376.pulsemetry.dashboard.source.ClickHouseSourceReader
import com.team376.pulsemetry.persistence.telemetryops.RetentionOperationStore
import com.team376.pulsemetry.persistence.telemetryops.TenantIngestSummaryStore
import com.team376.pulsemetry.persistence.telemetryops.TenantSummaryBackfill
import com.team376.pulsemetry.persistence.enrollment.management.VendorCatalog
import com.team376.pulsemetry.persistence.enrollment.operation.OperationReader
import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
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

	/** 작업 기록(`enrollment`)과 삭제 실행 기록(`telemetry_ops`)은 읽기만 한다 — 원천 계정. 쓰는 쪽은 다른 앱이다(ADR 0039). */
	@Bean
	fun operationReader(source: JdbcClient): OperationReader = OperationReader(source)

	@Bean
	fun retentionOperations(sourceDataSource: HikariDataSource): RetentionOperationStore = RetentionOperationStore(sourceDataSource)

	/** 두 기간 모두 완전 관측일 때만 비교를 공개한다(ADR 0042). */
	@Bean
	fun comparisonPolicy(): ComparisonPolicy = ComparisonPolicy.COMPLETE_ONLY

	@Bean
	fun analyticsFrames(
		snapshots: SnapshotService,
		references: SnapshotReferences,
		ingest: IngestStatusReader,
		comparison: ComparisonPolicy,
		properties: DashboardApiProperties,
		clock: Clock,
	): AnalyticsFrames = AnalyticsFrames(
		snapshots, references, ingest, comparison,
		IngestThresholds(properties.ingest.window, properties.ingest.delayedAfter, properties.ingest.downAfter),
		clock,
	)

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
		source: JdbcClient,
		seats: SeatService,
	): MembersService = MembersService(frames, aggregator, references, snapshots, codec, tokens,
		OrganizationPolicies(source, properties.members.idleDays), clock, managementEnabled, seats)

	/** 좌석 원장을 기준 시각으로 다시 세워 판정한다(ADR 0048 §7). 원천(원장·분석 행·설치·완전성 근거)은 읽기만 한다. */
	@Bean
	fun seatService(
		properties: DashboardApiProperties,
		source: JdbcClient,
		reader: ClickHouseSourceReader,
		mapper: ObjectMapper,
		boundaries: RetentionBoundaryReader,
		completeness: SnapshotCompleteness,
	): SeatService = SeatService(SeatLedgerReader(source, reader, mapper, boundaries, completeness),
		OrganizationPolicies(source, properties.members.idleDays), properties.seats.staleAfter)

	/** 설정·벤더의 관측 지표(ADR 0044). 원천(분석 행·매핑·완전성 근거)은 읽기만 하고 고정은 자기 캐시에 쓴다. */
	@Bean
	fun vendorObservations(
		reader: ClickHouseSourceReader,
		source: JdbcClient,
		@Qualifier(CacheStoreConfig.CACHE_DATA_SOURCE) cacheDataSource: HikariDataSource,
		completeness: SnapshotCompleteness,
		boundaries: RetentionBoundaryReader,
	): VendorObservations = VendorObservations(reader, source, JdbcClient.create(cacheDataSource),
		TransactionTemplate(DataSourceTransactionManager(cacheDataSource)), completeness, boundaries)

	@Bean
	fun settingsService(
		@org.springframework.beans.factory.annotation.Value("\${pulsemetry.management.enabled:false}") managementEnabled: Boolean,
		@org.springframework.beans.factory.annotation.Value("\${pulsemetry.mail.enabled:false}") mailEnabled: Boolean,
		properties: DashboardApiProperties,
		source: JdbcClient,
		observations: VendorObservations,
		frames: AnalyticsFrames,
		tokens: CurrentStateTokens,
		codec: PageCursorCodec,
		mapper: ObjectMapper,
		clock: Clock,
		catalog: VendorCatalog,
	): SettingsService = SettingsService(
		source, observations, frames, tokens, codec, mapper, OrganizationPolicies(source, properties.members.idleDays), clock, managementEnabled, catalog,
		// 안내는 enrollment-api 가 메일로 보낸다(ADR 0043). 두 앱이 같은 설정 값을 받는다.
		notificationsEnabled = managementEnabled && mailEnabled,
	)

	@Bean
	fun overviewService(frames: AnalyticsFrames, aggregator: UsageAggregator, references: SnapshotReferences): OverviewService =
		OverviewService(frames, aggregator, references)
}
