package com.team376.pulsemetry.persistence.telemetry

import com.team376.pulsemetry.telemetry.adapter.observation.EpochNanos
import com.team376.pulsemetry.telemetry.adapter.observation.EventObservation
import com.team376.pulsemetry.telemetry.adapter.observation.EventType
import com.team376.pulsemetry.telemetry.adapter.observation.MappingStatus
import com.team376.pulsemetry.telemetry.adapter.observation.MetricPointObservation
import com.team376.pulsemetry.telemetry.adapter.observation.MetricType
import com.team376.pulsemetry.telemetry.adapter.observation.ObservationEnvelope
import com.team376.pulsemetry.telemetry.adapter.observation.ObservationSignal
import com.team376.pulsemetry.telemetry.adapter.observation.OrgAttribution
import com.team376.pulsemetry.telemetry.adapter.observation.Product
import com.team376.pulsemetry.telemetry.adapter.observation.SeriesIdentityStatus
import com.team376.pulsemetry.telemetry.adapter.observation.SourceIdentityKind
import com.team376.pulsemetry.telemetry.adapter.observation.SourceTimeOrigin
import com.team376.pulsemetry.telemetry.adapter.observation.StructuralStatus
import com.team376.pulsemetry.telemetry.adapter.observation.Surface
import com.team376.pulsemetry.telemetry.adapter.observation.Temporality
import com.team376.pulsemetry.telemetry.adapter.observation.UsageRole
import com.team376.pulsemetry.telemetry.adapter.observation.UsageScope
import com.team376.pulsemetry.telemetry.adapter.observation.WorkloadKind
import com.team376.pulsemetry.telemetry.enricher.observation.EnrichedEvent
import com.team376.pulsemetry.telemetry.enricher.observation.EnrichedMetricPoint
import java.time.Instant

/**
 * 분석 행 인코더 테스트의 입력. 모든 선택 필드가 비어 있는 **최소 관측**이 기본이고, 테스트는 필요한 필드만 `copy` 로 채운다.
 * 보강·적재 단계의 값(`OrgAttribution`)도 최소형이다 — 무소속·미해결이 아닌 구성원 하나.
 */
internal object AnalysisSamples {

	const val TENANT = "33333333-3333-3333-3333-333333333333"
	const val INSTALLATION = "44444444-4444-4444-4444-444444444444"
	const val MEMBER = "55555555-5555-5555-5555-555555555555"

	val SOURCE_TIME: EpochNanos = EpochNanos.of(Instant.parse("2026-01-01T00:00:00.123456789Z"))
	val RECEIVED_TIME: EpochNanos = EpochNanos.of(Instant.parse("2026-01-01T00:00:05Z"))

	fun hex(seed: Char): String = seed.toString().repeat(64)

	fun envelope(signal: ObservationSignal = ObservationSignal.LOG, observationId: String = hex('a')) = ObservationEnvelope(
		tenantId = TENANT,
		installationId = INSTALLATION,
		observationId = observationId,
		analysisHash = hex('b'),
		identityVersion = "id-v1",
		sourceIdentityKind = SourceIdentityKind.FINGERPRINT,
		mappingStatus = MappingStatus.MAPPED,
		sourceTime = SOURCE_TIME,
		sourceTimeOrigin = if (signal == ObservationSignal.METRIC) SourceTimeOrigin.METRIC_POINT else SourceTimeOrigin.OTLP_EVENT,
		receivedTime = RECEIVED_TIME,
		signal = signal,
		product = Product.CLAUDE_CODE,
		surface = Surface.UNKNOWN,
		mappingVersion = "claude-code-v1",
		usageRole = UsageRole.NONE,
		usageScope = UsageScope.UNKNOWN,
		workloadKind = WorkloadKind.UNKNOWN,
		maskingVersion = "masking-v2",
		metadataJson = """{"record":[],"resource":[],"scope":[]}""",
	)

	fun org(memberId: String? = MEMBER, teamIds: List<String> = emptyList()) = OrgAttribution(
		memberId = memberId,
		teamIdAsOf = teamIds.singleOrNull(),
		teamIdsAsOf = teamIds,
		enrichmentJson = """{"ai_analysis":{},"github":{},"jira":{},"org":{"team_ids":[]}}""",
		enrichmentVersion = "enrichment-v1",
	)

	fun event(
		observation: EventObservation = EventObservation(envelope = envelope(), eventType = EventType.TURN),
		org: OrgAttribution = org(),
	) = EnrichedEvent(observation, org)

	fun metricPointObservation(observationId: String = hex('c')) = MetricPointObservation(
		envelope = envelope(ObservationSignal.METRIC, observationId),
		seriesIdentityStatus = SeriesIdentityStatus.UNVERIFIED,
		structuralStatus = StructuralStatus.VALID,
		metricName = "claude_code.token.usage",
		metricType = MetricType.SUM,
		rawUnit = "tokens",
		temporality = Temporality.DELTA,
	)

	fun metricPoint(observation: MetricPointObservation = metricPointObservation(), org: OrgAttribution = org()) =
		EnrichedMetricPoint(observation, org)
}
