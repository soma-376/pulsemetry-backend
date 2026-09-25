package com.team376.pulsemetry.telemetry.enricher.support

import com.team376.pulsemetry.telemetry.adapter.observation.AnalysisHash
import com.team376.pulsemetry.telemetry.adapter.observation.EpochNanos
import com.team376.pulsemetry.telemetry.adapter.observation.EventObservation
import com.team376.pulsemetry.telemetry.adapter.observation.EventType
import com.team376.pulsemetry.telemetry.adapter.observation.MappingStatus
import com.team376.pulsemetry.telemetry.adapter.observation.MetricPointObservation
import com.team376.pulsemetry.telemetry.adapter.observation.MetricType
import com.team376.pulsemetry.telemetry.adapter.observation.NormalizationStats
import com.team376.pulsemetry.telemetry.adapter.observation.ObservationBatch
import com.team376.pulsemetry.telemetry.adapter.observation.ObservationEnvelope
import com.team376.pulsemetry.telemetry.adapter.observation.ObservationIds
import com.team376.pulsemetry.telemetry.adapter.observation.ObservationSignal
import com.team376.pulsemetry.telemetry.adapter.observation.Product
import com.team376.pulsemetry.telemetry.adapter.observation.QualityFlag
import com.team376.pulsemetry.telemetry.adapter.observation.SeriesIdentityStatus
import com.team376.pulsemetry.telemetry.adapter.observation.SourceIdentityKind
import com.team376.pulsemetry.telemetry.adapter.observation.SourceTimeOrigin
import com.team376.pulsemetry.telemetry.adapter.observation.StructuralStatus
import com.team376.pulsemetry.telemetry.adapter.observation.Surface
import com.team376.pulsemetry.telemetry.adapter.observation.Temporality
import com.team376.pulsemetry.telemetry.adapter.observation.TypedMetadata
import com.team376.pulsemetry.telemetry.adapter.observation.UsageRole
import com.team376.pulsemetry.telemetry.adapter.observation.UsageScope
import com.team376.pulsemetry.telemetry.adapter.observation.WorkloadKind
import java.time.Instant

/**
 * 관측 모델 보강 테스트의 입력. 정규화 단계를 거친 것처럼 `analysis_hash` 를 봉인해 둔다 — 보강이 hash 를 건드리지
 * 않는다는 것을 그 값으로 확인한다. 보강이 읽는 것은 `installation_id` 와 `source_time` 뿐이라 나머지는 고정값이다.
 */
object TestObservations {

	fun event(
		installationId: String,
		at: Instant,
		observationId: String = "b".repeat(64),
		flags: List<QualityFlag> = emptyList(),
	): EventObservation = AnalysisHash.seal(
		EventObservation(envelope = envelope(installationId, at, observationId, ObservationSignal.LOG, flags), eventType = EventType.TURN),
	)

	fun metricPoint(installationId: String, at: Instant): MetricPointObservation = AnalysisHash.seal(
		MetricPointObservation(
			envelope = envelope(installationId, at, "c".repeat(64), ObservationSignal.METRIC, emptyList())
				.copy(sourceTimeOrigin = SourceTimeOrigin.METRIC_POINT),
			seriesIdentityStatus = SeriesIdentityStatus.UNVERIFIED,
			structuralStatus = StructuralStatus.VALID,
			metricName = "m",
			metricType = MetricType.SUM,
			rawUnit = "1",
			temporality = Temporality.DELTA,
			valueInt = 1,
		),
	)

	fun batch(
		events: List<EventObservation> = emptyList(),
		metricPoints: List<MetricPointObservation> = emptyList(),
	): ObservationBatch = ObservationBatch(events, metricPoints, NormalizationStats())

	private fun envelope(
		installationId: String,
		at: Instant,
		observationId: String,
		signal: ObservationSignal,
		flags: List<QualityFlag>,
	) = ObservationEnvelope(
		tenantId = "tenant",
		installationId = installationId,
		observationId = observationId,
		analysisHash = AnalysisHash.PLACEHOLDER,
		identityVersion = ObservationIds.IDENTITY_VERSION,
		sourceIdentityKind = SourceIdentityKind.FINGERPRINT,
		mappingStatus = MappingStatus.MAPPED,
		qualityFlags = flags,
		sourceTime = EpochNanos.of(at),
		sourceTimeOrigin = SourceTimeOrigin.OTLP_EVENT,
		receivedTime = EpochNanos.of(at),
		signal = signal,
		product = Product.CLAUDE_CODE,
		surface = Surface.UNKNOWN,
		mappingVersion = "test-v1",
		usageRole = UsageRole.NONE,
		usageScope = UsageScope.UNKNOWN,
		workloadKind = WorkloadKind.UNKNOWN,
		maskingVersion = "masking-test",
		metadataJson = TypedMetadata().toJson(),
	)
}
