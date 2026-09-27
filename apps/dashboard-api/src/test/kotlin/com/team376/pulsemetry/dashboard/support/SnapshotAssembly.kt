package com.team376.pulsemetry.dashboard.support

import com.team376.pulsemetry.dashboard.cache.ClickHouseCacheClient
import com.team376.pulsemetry.dashboard.snapshot.ModelResolution
import com.team376.pulsemetry.dashboard.snapshot.RetentionBoundaryReader
import com.team376.pulsemetry.dashboard.snapshot.SnapshotBuilder
import com.team376.pulsemetry.dashboard.snapshot.SnapshotCleaner
import com.team376.pulsemetry.dashboard.snapshot.SnapshotCopySql
import com.team376.pulsemetry.dashboard.snapshot.SnapshotManifestStore
import com.team376.pulsemetry.dashboard.snapshot.SnapshotReferenceCopier
import com.team376.pulsemetry.dashboard.snapshot.SnapshotService
import com.team376.pulsemetry.dashboard.store.ClickHouseConnection
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration

/**
 * 앱 컨텍스트 없이 snapshot 부품을 조립한다 — 원천 = 캐시 = 테스트 저장소의 관리 연결(권한 분리는 이 조립의 시험 대상이 아니다).
 * 부품을 새로 만들면 새 "인스턴스"다 — 상태는 저장소에만 있다.
 */
class SnapshotAssembly(
	val clock: Clock = Clock.systemUTC(),
	val limits: SnapshotBuilder.Limits = DEFAULT_LIMITS,
	resolution: ModelResolution = ModelResolution.NONE,
	sourceDatabase: String = "default",
	source: JdbcClient = DashboardTestStores.writer,
	httpClient: HttpClient = HttpClient.newHttpClient(),
) {
	private val cacheDataSource = DriverManagerDataSource(
		DashboardTestStores.postgres.jdbcUrl, DashboardTestStores.postgres.username, DashboardTestStores.postgres.password,
	)

	val clickHouse = ClickHouseCacheClient(
		ClickHouseConnection(
			DashboardTestStores.clickHouseUrl(), DashboardTestStores.CACHE_DATABASE, "default", "",
			Duration.ofSeconds(30), JsonMapper.builder().build(), httpClient,
		),
		Duration.ofSeconds(30),
	)
	val manifests = SnapshotManifestStore(JdbcClient.create(cacheDataSource), TransactionTemplate(DataSourceTransactionManager(cacheDataSource)))
	val boundaries = RetentionBoundaryReader(source)
	val builder = SnapshotBuilder(
		boundaries = boundaries,
		manifests = manifests,
		references = SnapshotReferenceCopier(source, JdbcClient.create(cacheDataSource)),
		clickHouse = clickHouse,
		sql = SnapshotCopySql(sourceDatabase, resolution),
		resolution = resolution,
		limits = limits,
		clock = clock,
	)
	val service = SnapshotService(builder, manifests, boundaries, clock)
	val cleaner = SnapshotCleaner(manifests, clickHouse, limits, clock)

	companion object {
		val DEFAULT_LIMITS = SnapshotBuilder.Limits(
			buildTimeout = Duration.ofSeconds(60),
			purgeGrace = Duration.ofSeconds(60),
			maxConcurrentBuilds = 4,
			maxCopyRows = 1_000_000,
			maxCopyBytes = 1_000_000_000,
		)
	}
}
