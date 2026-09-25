package com.team376.pulsemetry.telemetry.adapter.observation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** analysis_hash(ADR 0020 §3) — 정규화 출력의 동일성. 보강·수신 시각·영수증은 재료가 아니다. */
class AnalysisHashTest {

	private val id = "a".repeat(64)

	private fun metadata(vararg keys: String) =
		TypedMetadata(record = keys.map { TypedAttribute(it, TypedValue.Str("v-$it")) }).toJson()

	private fun event(
		metadataJson: String = metadata("event.kind", "model"),
		receivedTime: Long = 10,
		archiveRef: String? = null,
		maskingVersion: String = "masking-v2",
		tokensInput: Long? = 100,
		cost: BigDecimal? = null,
		usageRole: UsageRole = UsageRole.PRIMARY,
	) = EventObservation(
		envelope = ObservationEnvelope(
			tenantId = "t", installationId = "i", observationId = id, analysisHash = AnalysisHash.PLACEHOLDER,
			identityVersion = ObservationIds.IDENTITY_VERSION, sourceIdentityKind = SourceIdentityKind.FINGERPRINT,
			mappingStatus = MappingStatus.MAPPED, sourceTime = EpochNanos(5), sourceTimeOrigin = SourceTimeOrigin.OTLP_OBSERVED,
			receivedTime = EpochNanos(receivedTime), signal = ObservationSignal.LOG, product = Product.CODEX,
			surface = Surface.APP_SERVER, mappingVersion = "codex-v1", usageRole = usageRole, usageScope = UsageScope.RESPONSE,
			workloadKind = WorkloadKind.UNKNOWN, archiveRef = archiveRef, archiveSelector = archiveRef?.let { "path=0/0/0" },
			maskingVersion = maskingVersion, metadataJson = metadataJson,
		),
		eventType = EventType.MODEL_RESPONSE_USAGE,
		tokensInput = tokensInput,
		costReportedUsd = cost,
		decisionSource = DecisionSource.CONFIG,
	)

	@Test
	@DisplayName("같은 결과는 언제나 같은 hash 다 — 64자리 소문자 hex")
	fun deterministic() {
		assertThat(AnalysisHash.of(event())).isEqualTo(AnalysisHash.of(event()))
		assertThat(Hex64.isValid(AnalysisHash.of(event()))).isTrue()
	}

	@Test
	@DisplayName("metadata 의 속성 순서만 다르면 같은 hash 다 — 중복 개수는 재료다")
	fun metadataOrderIsNotMaterial() {
		assertThat(AnalysisHash.of(event(metadataJson = metadata("model", "event.kind"))))
			.isEqualTo(AnalysisHash.of(event(metadataJson = metadata("event.kind", "model"))))
		assertThat(AnalysisHash.of(event(metadataJson = metadata("model", "model", "event.kind"))))
			.isNotEqualTo(AnalysisHash.of(event()))
	}

	@Test
	@DisplayName("수신 시각·영수증(archive_ref·selector·masking_version)은 재료가 아니다")
	fun receiptAndReceiveTimeAreNotMaterial() {
		val base = AnalysisHash.of(event())

		assertThat(AnalysisHash.of(event(receivedTime = 999))).isEqualTo(base)
		assertThat(AnalysisHash.of(event(archiveRef = "s3://raw/codex/logs/x.json"))).isEqualTo(base)
		assertThat(AnalysisHash.of(event(maskingVersion = "masking-v9"))).isEqualTo(base)
		val missing = event().let { it.copy(envelope = it.envelope.copy(qualityFlags = listOf(QualityFlag.ARCHIVE_RECEIPT_MISSING))) }
		assertThat(AnalysisHash.of(missing)).isEqualTo(base)
	}

	@Test
	@DisplayName("조직 보강은 관측 타입 밖이다 — 소속이 바뀐 재처리 두 행의 hash 는 같다")
	fun enrichmentIsNotMaterial() {
		val observation = AnalysisHash.seal(event())
		val before = observation to OrgAttribution("m", "team-a", listOf("team-a"), "{}", "org-v1")
		val after = observation to OrgAttribution("m", "team-b", listOf("team-b"), "{}", "org-v1")

		assertThat(before.first.envelope.analysisHash).isEqualTo(after.first.envelope.analysisHash)
	}

	@Test
	@DisplayName("측정값·분류가 다르면 다른 hash 다 — null 과 0 도 다르다")
	fun analysisOutputIsMaterial() {
		val base = AnalysisHash.of(event())

		assertThat(AnalysisHash.of(event(tokensInput = 101))).isNotEqualTo(base)
		assertThat(AnalysisHash.of(event(tokensInput = null))).isNotEqualTo(AnalysisHash.of(event(tokensInput = 0)))
		assertThat(AnalysisHash.of(event(usageRole = UsageRole.DIAGNOSTIC))).isNotEqualTo(base)
	}

	@Test
	@DisplayName("금액은 값이 같으면 같은 표기다 — 0.020 과 0.02")
	fun decimalsAreCanonical() {
		assertThat(AnalysisHash.of(event(cost = BigDecimal("0.020")))).isEqualTo(AnalysisHash.of(event(cost = BigDecimal("0.02"))))
	}

	@Test
	@DisplayName("seal 은 hash 를 넣고, 다시 seal 해도 그대로다")
	fun sealIsIdempotent() {
		val sealed = AnalysisHash.seal(event())

		assertThat(sealed.envelope.analysisHash).isEqualTo(AnalysisHash.of(event()))
		assertThat(AnalysisHash.seal(sealed)).isEqualTo(sealed)
	}

	@Test
	@DisplayName("metric point 도 같은 규칙이다")
	fun metricPoints() {
		fun point(value: Long) = MetricPointObservation(
			envelope = event().envelope.copy(signal = ObservationSignal.METRIC, sourceTimeOrigin = SourceTimeOrigin.METRIC_POINT),
			seriesIdentityStatus = SeriesIdentityStatus.UNVERIFIED, structuralStatus = StructuralStatus.VALID,
			metricName = "m", metricType = MetricType.SUM, rawUnit = "1", temporality = Temporality.DELTA,
			valueInt = value, bucketCounts = listOf(ULong.MAX_VALUE),
		)

		assertThat(AnalysisHash.of(point(1))).isEqualTo(AnalysisHash.of(point(1)))
		assertThat(AnalysisHash.of(point(1))).isNotEqualTo(AnalysisHash.of(point(2)))
	}
}
