package com.team376.pulsemetry.telemetry.config

import com.team376.pulsemetry.persistence.telemetry.ClickHouseHttpClient
import com.team376.pulsemetry.persistence.telemetry.TelemetryEventsSink
import com.team376.pulsemetry.persistence.telemetry.TelemetryMetricPointsSink
import com.team376.pulsemetry.telemetry.collector.OtlpIngestProcessor
import com.team376.pulsemetry.telemetry.collector.archive.ArchiveWriter
import com.team376.pulsemetry.telemetry.consumer.batch.BatchDeliveryState
import com.team376.pulsemetry.telemetry.consumer.batch.BatchIngestProcessor
import com.team376.pulsemetry.telemetry.consumer.batch.BatchSettings
import com.team376.pulsemetry.telemetry.consumer.batch.HandoffListWriter
import com.team376.pulsemetry.telemetry.consumer.batch.TelemetryBatchListener
import com.team376.pulsemetry.telemetry.credential.TelemetryCredentialAuthorizer
import com.team376.pulsemetry.telemetry.observability.TelemetryObserver
import com.team376.pulsemetry.telemetry.pipeline.BatchIngestOperations
import com.team376.pulsemetry.telemetry.pipeline.ClickHouseSchema
import com.team376.pulsemetry.telemetry.pipeline.IngestStages
import com.team376.pulsemetry.telemetry.pipeline.RetentionBoundaries
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Kafka 배치 소비의 조립(PROJ-341 · ADR 0083). `pulsemetry.telemetry.kafka.consumer.batch.enabled=true` 일 때만 선다 — 꺼져 있으면 이 빈들이
 * 없고 [KafkaConsumerConfig] 가 단건 리스너를 쓴다.
 *
 * 단계 구현은 단건 경로와 같은 인스턴스다 — 인가·수집 코어(준비)·정규화·보강·삭제 경계·sink·운영 기록. 다른 것은 묶는 단위와 실패의 끝맺음
 * (재시도·넘김·커밋)이다. 운영 기록이 꺼져 있으면([OpsConfig]) ledger·요약을 건너뛴다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pulsemetry.telemetry.kafka.consumer.batch", name = ["enabled"], havingValue = "true")
class KafkaBatchConfig {

	@Bean
	fun batchSettings(properties: TelemetryIngestProperties): BatchSettings = BatchSettings.of(properties)

	@Bean
	fun batchDeliveryState(): BatchDeliveryState = BatchDeliveryState()

	/** 배치 안 아카이브 PUT 을 동시에 돌리는 실행기. 동시 수는 배치마다 세마포어로 제한한다. */
	@Bean(destroyMethod = "close")
	fun batchArchiveExecutor(): ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

	@Bean
	fun batchIngestProcessor(
		authorizer: TelemetryCredentialAuthorizer,
		processor: OtlpIngestProcessor,
		archive: ArchiveWriter,
		stages: IngestStages,
		schema: ClickHouseSchema,
		boundaries: RetentionBoundaries,
		events: TelemetryEventsSink,
		metricPoints: TelemetryMetricPointsSink,
		operations: ObjectProvider<BatchIngestOperations>,
		handoffs: HandoffListWriter,
		clickHouse: ClickHouseHttpClient,
		settings: BatchSettings,
		batchArchiveExecutor: ExecutorService,
		observer: TelemetryObserver,
		clock: Clock,
	): BatchIngestProcessor = BatchIngestProcessor(
		authorizer = authorizer,
		preparer = processor,
		archive = archive,
		stages = stages,
		schema = schema,
		boundaries = boundaries,
		events = events,
		metricPoints = metricPoints,
		operations = operations.ifAvailable,
		handoffs = handoffs,
		probe = { clickHouse.execute("SELECT 1", operation = "select", table = "other", requestTimeout = settings.probeTimeout) },
		settings = settings,
		archiveExecutor = batchArchiveExecutor,
		observer = observer,
		clock = clock,
	)

	@Bean
	fun telemetryBatchListener(
		processor: BatchIngestProcessor,
		state: BatchDeliveryState,
		settings: BatchSettings,
		observer: TelemetryObserver,
		clock: Clock,
	): TelemetryBatchListener = TelemetryBatchListener(processor, state, settings, observer, clock)
}
