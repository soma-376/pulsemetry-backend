package com.team376.pulsemetry.telemetry.config

import com.team376.pulsemetry.persistence.enrollment.repository.InstallationRepository
import com.team376.pulsemetry.persistence.enrollment.repository.TeamMembershipRepository
import com.team376.pulsemetry.persistence.telemetry.TelemetryEventsSink
import com.team376.pulsemetry.persistence.telemetry.TelemetryMetricPointsSink
import com.team376.pulsemetry.telemetry.adapter.observation.ObservationNormalizer
import com.team376.pulsemetry.telemetry.adapter.observation.ObservationPipeline
import com.team376.pulsemetry.telemetry.adapter.observation.claudecode.ClaudeCodeProfile
import com.team376.pulsemetry.telemetry.adapter.observation.codex.CodexProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.ProfileRegistry
import com.team376.pulsemetry.telemetry.collector.IdentitySource
import com.team376.pulsemetry.telemetry.collector.OtlpIngestHandler
import com.team376.pulsemetry.telemetry.collector.SignalConsumer
import com.team376.pulsemetry.telemetry.collector.archive.ArchiveWriter
import com.team376.pulsemetry.telemetry.enricher.observation.ObservationEnricher
import com.team376.pulsemetry.telemetry.enricher.provider.AiAnalysisProvider
import com.team376.pulsemetry.telemetry.enricher.provider.GithubProvider
import com.team376.pulsemetry.telemetry.enricher.provider.JiraProvider
import com.team376.pulsemetry.telemetry.pipeline.ClickHouseSchema
import com.team376.pulsemetry.telemetry.pipeline.IngestOperations
import com.team376.pulsemetry.telemetry.pipeline.IngestPipeline
import com.team376.pulsemetry.telemetry.pipeline.SecurityContextIdentitySource
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * 다섯 단계를 잇는다. **여기가 조립의 전부다** — 단계 모듈은 서로의 seam 을 구현하지 않고
 * (ADR 0013 · 0014) 배선은 앱이 한다(ADR 0011).
 */
@Configuration(proxyBeanMethods = false)
class PipelineConfig {

	/**
	 * 정규화 2판. 제품 프로파일은 근거 기록과 실캡처 fixture 로 확인한 둘이다(ADR 0020 부록 A·C). 가격 프로파일은
	 * 주입하지 않는다 — 가격 데이터의 관리 주체가 정해지기 전에는 추정 비용이 null 이다(ADR 0020 Follow-up).
	 */
	@Bean
	fun observationPipeline(): ObservationPipeline =
		ObservationPipeline(ObservationNormalizer(ProfileRegistry(listOf(CodexProfile, ClaudeCodeProfile))))

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

	/** 수집 운영 기록은 설정으로 켤 때만 있다(`OpsConfig`) — 없으면 파이프라인이 그 단계를 건너뛴다. */
	@Bean
	fun signalConsumer(
		observations: ObservationPipeline,
		enricher: ObservationEnricher,
		events: TelemetryEventsSink,
		metricPoints: TelemetryMetricPointsSink,
		schema: ClickHouseSchema,
		operations: ObjectProvider<IngestOperations>,
	): SignalConsumer = IngestPipeline(observations::run, enricher, events, metricPoints, schema, operations.ifAvailable)

	@Bean
	fun identitySource(): IdentitySource = SecurityContextIdentitySource()

	@Bean
	fun otlpIngestHandler(
		archive: ArchiveWriter,
		next: SignalConsumer,
		identity: IdentitySource,
		properties: TelemetryIngestProperties,
		clock: Clock,
	): OtlpIngestHandler = OtlpIngestHandler(
		archive = archive,
		next = next,
		identity = identity,
		maxDecompressedBytes = properties.telemetry.ingest.maxDecompressedBytes,
		// 아카이브 영수증의 수신 시각. S3 키 파티션과 같은 시계다.
		clock = clock,
	)
}
