package com.team376.pulsemetry.telemetry.adapter.observation

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/** 관측 타입이 스스로 지키는 불변식. 값의 해석(null 과 품질 플래그)은 매핑의 몫이고, 여기는 표현의 한계다. */
class ObservationInvariantsTest {

	private val hex = "0".repeat(63) + "1"

	private fun envelope(signal: ObservationSignal = ObservationSignal.LOG) = ObservationEnvelope(
		tenantId = "tenant",
		installationId = "installation",
		observationId = hex,
		analysisHash = hex,
		identityVersion = "v1",
		sourceIdentityKind = SourceIdentityKind.FINGERPRINT,
		mappingStatus = MappingStatus.GENERIC,
		sourceTime = EpochNanos(1),
		sourceTimeOrigin = SourceTimeOrigin.OTLP_EVENT,
		receivedTime = EpochNanos(2),
		signal = signal,
		product = Product.UNKNOWN,
		surface = Surface.UNKNOWN,
		mappingVersion = "m",
		usageRole = UsageRole.NONE,
		usageScope = UsageScope.UNKNOWN,
		workloadKind = WorkloadKind.UNKNOWN,
		maskingVersion = "masking-v2",
		metadataJson = TypedMetadata.EMPTY.toJson(),
	)

	@Test
	@DisplayName("관측 ID·analysis_hash 는 64자리 소문자 hex 다")
	fun idsAreLowercaseHex64() {
		assertThatThrownBy { envelope().copy(observationId = hex.uppercase().replace('0', 'A')) }
			.isInstanceOf(IllegalArgumentException::class.java)
		assertThatThrownBy { envelope().copy(analysisHash = "abc") }.isInstanceOf(IllegalArgumentException::class.java)
	}

	@Test
	@DisplayName("archive_ref 와 selector 는 함께 있다 — 영수증이 없으면 둘 다 null")
	fun archivePointerIsAllOrNothing() {
		assertThatThrownBy { envelope().copy(archiveRef = "s3://b/k") }.isInstanceOf(IllegalArgumentException::class.java)
		assertThat(envelope().copy(archiveRef = "s3://b/k", archiveSelector = "path=0/0/0").archiveRef).isEqualTo("s3://b/k")
	}

	@Test
	@DisplayName("토큰은 음수가 아니다 — 0 은 유효한 측정이다")
	fun tokensAreNonNegative() {
		assertThat(EventObservation(envelope(), EventType.MODEL_RESPONSE_USAGE, tokensOutput = 0).tokensOutput).isZero()
		assertThatThrownBy { EventObservation(envelope(), EventType.MODEL_RESPONSE_USAGE, tokensInput = -1) }
			.isInstanceOf(IllegalArgumentException::class.java)
	}

	@Test
	@DisplayName("추정 비용은 pricing_version 과 함께만 있다")
	fun estimatedCostNeedsPricingVersion() {
		assertThatThrownBy { EventObservation(envelope(), EventType.MODEL_RESPONSE_USAGE, costEstimatedUsd = BigDecimal("0.01")) }
			.isInstanceOf(IllegalArgumentException::class.java)
	}

	@Test
	@DisplayName("신호와 타입이 맞아야 한다 — 로그·스팬은 EventObservation, point 는 MetricPointObservation")
	fun signalMatchesTheTable() {
		assertThatThrownBy { EventObservation(envelope(ObservationSignal.METRIC), EventType.DIAGNOSTIC) }
			.isInstanceOf(IllegalArgumentException::class.java)
		assertThatThrownBy {
			MetricPointObservation(
				envelope(ObservationSignal.LOG), seriesIdentityStatus = SeriesIdentityStatus.MISSING,
				structuralStatus = StructuralStatus.VALID, metricName = "m", metricType = MetricType.GAUGE,
				rawUnit = "", temporality = Temporality.NONE,
			)
		}.isInstanceOf(IllegalArgumentException::class.java)
	}

	@Test
	@DisplayName("metric point — oneof 는 하나만, 비유한 double 은 담지 않는다, UInt64 전 범위")
	fun metricPointRepresentation() {
		fun point(valueInt: Long? = null, valueDouble: Double? = null) = MetricPointObservation(
			envelope(ObservationSignal.METRIC), seriesIdentityStatus = SeriesIdentityStatus.UNVERIFIED,
			structuralStatus = StructuralStatus.VALID, metricName = "m", metricType = MetricType.GAUGE,
			rawUnit = "", temporality = Temporality.NONE, valueInt = valueInt, valueDouble = valueDouble,
			bucketCounts = listOf(ULong.MAX_VALUE),
		)

		assertThat(point(valueInt = 1).bucketCounts.single()).isEqualTo(ULong.MAX_VALUE)
		assertThatThrownBy { point(valueInt = 1, valueDouble = 1.0) }.isInstanceOf(IllegalArgumentException::class.java)
		assertThatThrownBy { point(valueDouble = Double.NaN) }.isInstanceOf(IllegalArgumentException::class.java)
	}

	@Test
	@DisplayName("row_version = (normalizer_rev << 32) | ingest_seq")
	fun rowVersionComposition() {
		val versioning = RowVersioning.of(normalizerRev = 2u, ingestSeq = UInt.MAX_VALUE)

		assertThat(versioning.rowVersion).isEqualTo((2uL shl 32) or 0xFFFF_FFFFuL)
		assertThat(RowVersioning.of(3u, 0u).rowVersion).isGreaterThan(versioning.rowVersion)
		assertThatThrownBy { RowVersioning(normalizerRev = 1u, rowVersion = 5uL) }.isInstanceOf(IllegalArgumentException::class.java)
	}

	@Test
	@DisplayName("live 수신의 ingest_seq 는 receipt 수신 시각의 epoch 초다 — UInt32 밖은 거부, normalizer_rev 는 1 부터")
	fun liveVersioning() {
		val receivedAt = Instant.parse("2026-01-01T00:00:05.999Z")

		assertThat(RowVersioning.NORMALIZER_REV).isEqualTo(1u)
		assertThat(RowVersioning.live(receivedAt)).isEqualTo(RowVersioning.of(1u, 1_767_225_605u))
		assertThat(RowVersioning.live(Instant.ofEpochSecond(0xFFFF_FFFFL), normalizerRev = 2u).rowVersion)
			.isEqualTo((2uL shl 32) or 0xFFFF_FFFFuL)
		assertThatThrownBy { RowVersioning.live(Instant.ofEpochSecond(0x1_0000_0000L)) }.isInstanceOf(IllegalArgumentException::class.java)
		assertThatThrownBy { RowVersioning.live(Instant.parse("1969-12-31T23:59:59Z")) }.isInstanceOf(IllegalArgumentException::class.java)
	}

	@Test
	@DisplayName("EpochNanos 는 나노초까지 Instant 와 왕복한다")
	fun epochNanosRoundTrip() {
		val instant = Instant.parse("2026-09-25T01:02:03.123456789Z")

		assertThat(EpochNanos.of(instant).toInstant()).isEqualTo(instant)
		assertThat(EpochNanos.of(Instant.parse("1969-12-31T23:59:59.999999999Z")).value).isEqualTo(-1)
	}
}
