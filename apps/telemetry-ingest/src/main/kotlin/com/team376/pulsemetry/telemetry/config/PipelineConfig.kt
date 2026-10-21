package com.team376.pulsemetry.telemetry.config

import com.team376.pulsemetry.persistence.enrollment.repository.InstallationRepository
import com.team376.pulsemetry.persistence.enrollment.repository.TeamMembershipRepository
import com.team376.pulsemetry.persistence.telemetry.TelemetryEventsSink
import com.team376.pulsemetry.persistence.telemetry.TelemetryMetricPointsSink
import com.team376.pulsemetry.persistence.telemetryops.TenantRetentionBoundaryStore
import com.team376.pulsemetry.telemetry.adapter.observation.ObservationNormalizer
import com.team376.pulsemetry.telemetry.adapter.observation.ObservationPipeline
import com.team376.pulsemetry.telemetry.adapter.observation.claudecode.ClaudeApiProfile
import com.team376.pulsemetry.telemetry.adapter.observation.claudecode.ClaudeCodeProfile
import com.team376.pulsemetry.telemetry.adapter.observation.codex.CodexProfile
import com.team376.pulsemetry.telemetry.adapter.observation.codex.CodexCompletedProfile
import com.team376.pulsemetry.telemetry.adapter.observation.codex.CodexDesktopCompletedProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.ProfileRegistry
import com.team376.pulsemetry.telemetry.adapter.observation.pricing.CurrentPublicPrices
import com.team376.pulsemetry.telemetry.collector.OtlpIngestHandler
import com.team376.pulsemetry.telemetry.collector.OtlpIngestProcessor
import com.team376.pulsemetry.telemetry.collector.OtlpProcessor
import com.team376.pulsemetry.telemetry.collector.SignalConsumer
import com.team376.pulsemetry.telemetry.collector.archive.ArchiveWriter
import com.team376.pulsemetry.telemetry.enricher.observation.ObservationEnricher
import com.team376.pulsemetry.telemetry.enricher.provider.AiAnalysisProvider
import com.team376.pulsemetry.telemetry.enricher.provider.GithubProvider
import com.team376.pulsemetry.telemetry.enricher.provider.JiraProvider
import com.team376.pulsemetry.telemetry.pipeline.ClickHouseSchema
import com.team376.pulsemetry.telemetry.pipeline.IngestOperations
import com.team376.pulsemetry.telemetry.pipeline.IngestPipeline
import com.team376.pulsemetry.telemetry.pipeline.RdsRetentionBoundaries
import com.team376.pulsemetry.telemetry.pipeline.RetentionBoundaries
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import javax.sql.DataSource

/**
 * 다섯 단계를 잇는다. **여기가 조립의 전부다** — 단계 모듈은 서로의 seam 을 구현하지 않고
 * (ADR 0013 · 0014) 배선은 앱이 한다(ADR 0011).
 */
@Configuration(proxyBeanMethods = false)
class PipelineConfig {

	/**
	 * 제품 프로파일은 근거와 실캡처로 확인한 범위다. 가격은 고정된 현재 공시 표준 환산이다(ADR 0060).
	 * 미검증 의미와 필요한 성분이 없는 행은 프로파일을 주입해도 null이다.
	 */
	@Bean
	fun observationPipeline(properties: TelemetryIngestProperties): ObservationPipeline =
		ObservationPipeline(
			ObservationNormalizer(ProfileRegistry(listOf(CodexProfile, CodexCompletedProfile, CodexDesktopCompletedProfile, ClaudeCodeProfile, ClaudeApiProfile))),
			CurrentPublicPrices.select(properties.telemetry.ingest.pricingProfile),
		)

	/**
	 * 보강 2판. **스텁 provider 셋을 등록한다** — 아무것도 하지 않지만 `enrichment_json` 에 빈 항목을 쓴다(ADR 0017
	 * 규칙 8). `org` 항목은 `ObservationEnricher` 가 직접 쓰므로 목록에 넣지 않는다.
	 */
	@Bean
	fun observationEnricher(
		installations: InstallationRepository,
		teamMemberships: TeamMembershipRepository,
	): ObservationEnricher = ObservationEnricher(
		installations,
		teamMemberships,
		listOf(GithubProvider(), JiraProvider(), AiAnalysisProvider()),
	)

	/**
	 * 삭제 경계(ADR 0024 §3). **설정으로 끄지 않는다** — 수집 운영 기록과 달리 쓰기가 아니라 집행이고, 끌 수 있으면 보존 삭제가
	 * 재전송으로 되살아난다. `telemetry_ops` 는 보강과 같은 RDS 라 같은 `DataSource` 다. 스키마가 아직 없으면 503 이다.
	 */
	@Bean
	fun tenantRetentionBoundaryStore(dataSource: DataSource): TenantRetentionBoundaryStore = TenantRetentionBoundaryStore(dataSource)

	@Bean
	fun retentionBoundaries(store: TenantRetentionBoundaryStore): RetentionBoundaries = RdsRetentionBoundaries(store)

	/** 수집 운영 기록은 설정으로 켤 때만 있다(`OpsConfig`) — 없으면 파이프라인이 그 단계를 건너뛴다. */
	@Bean
	fun signalConsumer(
		observations: ObservationPipeline,
		enricher: ObservationEnricher,
		events: TelemetryEventsSink,
		metricPoints: TelemetryMetricPointsSink,
		schema: ClickHouseSchema,
		boundaries: RetentionBoundaries,
		operations: ObjectProvider<IngestOperations>,
	): SignalConsumer = IngestPipeline(observations::run, enricher, events, metricPoints, schema, boundaries, operations.ifAvailable)

	/**
	 * 전송 독립 수집 코어(ADR 0067). HTTP 어댑터와 Kafka Consumer 가 같은 인스턴스를 부른다 — 압축 해제 상한·마스킹·
	 * 스탬프·아카이브 규칙이 두 경로에서 갈라지지 않는다.
	 */
	@Bean
	fun otlpProcessor(
		archive: ArchiveWriter,
		next: SignalConsumer,
		properties: TelemetryIngestProperties,
	): OtlpProcessor = OtlpIngestProcessor(
		archive = archive,
		next = next,
		maxDecompressedBytes = properties.telemetry.ingest.maxDecompressedBytes,
	)

	/** HTTP 어댑터. 신원은 컨트롤러가 인증된 principal 에서 꺼내 명시적으로 넘긴다(ADR 0067 — 스레드 지역 신원을 읽지 않는다). */
	@Bean
	fun otlpIngestHandler(processor: OtlpProcessor, clock: Clock): OtlpIngestHandler =
		// 아카이브 영수증의 수신 시각. S3 키 파티션과 같은 시계다.
		OtlpIngestHandler(processor, clock)
}
