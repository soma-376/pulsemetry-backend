package com.team376.pulsemetry.retention.config

import com.team376.pulsemetry.persistence.telemetry.ClickHouseHttpClient
import com.team376.pulsemetry.persistence.telemetry.InsertDrain
import com.team376.pulsemetry.persistence.telemetry.RetentionFence
import com.team376.pulsemetry.persistence.telemetry.RetentionPurge
import com.team376.pulsemetry.persistence.telemetryops.RetentionOperationStore
import com.team376.pulsemetry.persistence.telemetryops.TenantRetentionBoundaryStore
import com.team376.pulsemetry.persistence.enrollment.operation.OperationStore
import com.team376.pulsemetry.persistence.enrollment.operation.RetentionCleanupRequests
import com.team376.pulsemetry.retention.RetentionCommandRunner
import com.team376.pulsemetry.retention.RetentionJob
import com.team376.pulsemetry.retention.RetentionRequestProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock
import javax.sql.DataSource

/**
 * 보존 작업을 잇는다. **여기가 조립의 전부다** — 쓰기 소유 모듈의 클래스는 빈이 아니다(ADR 0011).
 *
 * 이 앱은 DDL 을 적용하지 않는다 — `telemetry_ops` 는 `:apps:enrollment-api`, ClickHouse 는 `:apps:telemetry-ingest` 가 적용한다(ADR 0021 §3 ·
 * ADR 0015). 테이블이 없으면 실행이 `failed` 로 끝난다.
 */
@Configuration(proxyBeanMethods = false)
class RetentionWorkerConfig {

	@Bean
	fun clock(): Clock = Clock.systemUTC()

	@Bean
	fun clickHouseHttpClient(properties: RetentionWorkerProperties): ClickHouseHttpClient =
		ClickHouseHttpClient(properties.clickhouse.url, properties.clickhouse.database, properties.clickhouse.timeout)

	@Bean
	fun tenantRetentionBoundaryStore(dataSource: DataSource): TenantRetentionBoundaryStore = TenantRetentionBoundaryStore(dataSource)

	@Bean
	fun retentionOperationStore(dataSource: DataSource): RetentionOperationStore = RetentionOperationStore(dataSource)

	@Bean
	fun retentionJob(
		boundaries: TenantRetentionBoundaryStore,
		operations: RetentionOperationStore,
		client: ClickHouseHttpClient,
		properties: RetentionWorkerProperties,
		clock: Clock,
	): RetentionJob = RetentionJob(
		boundaries = boundaries,
		operations = operations,
		fence = RetentionFence(client),
		drain = InsertDrain(client),
		purge = RetentionPurge(client),
		settings = RetentionJob.Settings(properties.drain.timeout, properties.drain.pollInterval, properties.maxPasses),
		clock = clock,
	)

	/** 요청 모드(ADR 0047) — enrollment 의 보존 정리 요청과 공통 작업 기록을 같은 DB 에서 쓴다. */
	@Bean
	fun retentionRequestProcessor(
		dataSource: DataSource,
		transactionManager: PlatformTransactionManager,
		job: RetentionJob,
		properties: RetentionWorkerProperties,
		clock: Clock,
	): RetentionRequestProcessor {
		val jdbc = JdbcClient.create(dataSource)
		return RetentionRequestProcessor(RetentionCleanupRequests(jdbc, transactionManager, OperationStore(jdbc, transactionManager, clock)), job, clock,
			properties.requests?.lease, properties.requests?.maxRuns)
	}

	@Bean
	fun retentionCommandRunner(job: RetentionJob, requests: RetentionRequestProcessor): RetentionCommandRunner = RetentionCommandRunner(job, requests)
}
