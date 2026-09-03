package com.team376.pulsemetry.telemetry.config

import com.team376.pulsemetry.persistence.enrollment.repository.TeamMembershipRepository
import com.team376.pulsemetry.persistence.telemetry.EnrichedEventsSink
import com.team376.pulsemetry.telemetry.collector.IdentitySource
import com.team376.pulsemetry.telemetry.collector.OtlpIngestHandler
import com.team376.pulsemetry.telemetry.collector.SignalConsumer
import com.team376.pulsemetry.telemetry.collector.archive.ArchiveWriter
import com.team376.pulsemetry.telemetry.enricher.Enricher
import com.team376.pulsemetry.telemetry.enricher.provider.AiAnalysisProvider
import com.team376.pulsemetry.telemetry.enricher.provider.GithubProvider
import com.team376.pulsemetry.telemetry.enricher.provider.JiraProvider
import com.team376.pulsemetry.telemetry.enricher.provider.OrgProvider
import com.team376.pulsemetry.telemetry.pipeline.ClickHouseSchema
import com.team376.pulsemetry.telemetry.pipeline.IngestPipeline
import com.team376.pulsemetry.telemetry.pipeline.SecurityContextIdentitySource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 다섯 단계를 잇는다. **여기가 조립의 전부다** — 단계 모듈은 서로의 seam 을 구현하지 않고
 * (ADR 0013 · 0014) 배선은 앱이 한다(ADR 0011).
 */
@Configuration(proxyBeanMethods = false)
class PipelineConfig {

	/**
	 * **네 provider 를 전부 등록한다.** github·jira·ai_analysis 는 아무것도 하지 않지만,
	 * 구 registry 가 발견된 모든 provider 에 항상 항목을 쓰기 때문에 빼면 저장되는
	 * `enrichment_json` 의 바이트가 달라진다.
	 */
	@Bean
	fun enricher(teamMemberships: TeamMembershipRepository): Enricher = Enricher(
		listOf(
			OrgProvider(teamMemberships),
			GithubProvider(),
			JiraProvider(),
			AiAnalysisProvider(),
		),
	)

	@Bean
	fun signalConsumer(
		enricher: Enricher,
		sink: EnrichedEventsSink,
		schema: ClickHouseSchema,
	): SignalConsumer = IngestPipeline(enricher, sink, schema)

	@Bean
	fun identitySource(): IdentitySource = SecurityContextIdentitySource()

	@Bean
	fun otlpIngestHandler(
		archive: ArchiveWriter,
		next: SignalConsumer,
		identity: IdentitySource,
		properties: TelemetryIngestProperties,
	): OtlpIngestHandler = OtlpIngestHandler(
		archive = archive,
		next = next,
		identity = identity,
		maxDecompressedBytes = properties.telemetry.ingest.maxDecompressedBytes,
	)
}
