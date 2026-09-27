package com.team376.pulsemetry.telemetry.adapter.observation

/**
 * `telemetry_metric_points` 한 행의 정규화 몫 — 봉투([envelope]) + metric point 고유 36컬럼
 * (ADR 0020 §1·§4, 부록 D.2). point 하나가 관측 하나다.
 *
 * 원본 oneof·구조를 그대로 담는다 — histogram·summary 를 스칼라로 납작하게 만들지 않는다. 해당하지 않는
 * 배열은 빈 배열, 선택 수치는 null 이다. no-recorded-value(`point_flags`)는 0 측정이 아니다.
 */
public data class MetricPointObservation(
	public val envelope: ObservationEnvelope,
	/** 64자리 소문자 hex. 안정적으로 만들 수 없으면 null — KPI 집계에 쓰지 않는다. */
	public val seriesId: String? = null,
	public val seriesIdentityStatus: SeriesIdentityStatus,
	public val structuralStatus: StructuralStatus,
	public val metricName: String,
	public val metricType: MetricType,
	public val metricFamily: MetricFamily = MetricFamily.VENDOR_METRIC,
	public val metricProfile: String? = null,
	public val description: String? = null,
	/** descriptor 의 unit 그대로. 빈 값은 "단위 미지정"이다. */
	public val rawUnit: String,
	public val canonicalUnit: CanonicalUnit = CanonicalUnit.UNKNOWN,
	public val tokenComponent: TokenComponent = TokenComponent.NONE,
	public val temporality: Temporality,
	public val temporalityCode: Int? = null,
	public val isMonotonic: Boolean? = null,
	public val startTime: EpochNanos? = null,
	public val valueInt: Long? = null,
	public val valueDouble: Double? = null,
	public val histCount: ULong? = null,
	public val histSum: Double? = null,
	public val histMin: Double? = null,
	public val histMax: Double? = null,
	public val histBucketsPresent: Boolean = false,
	public val explicitBounds: List<Double> = emptyList(),
	public val bucketCounts: List<ULong> = emptyList(),
	public val expScale: Int? = null,
	public val expZeroCount: ULong? = null,
	public val expZeroThreshold: Double? = null,
	public val expPositiveOffset: Int? = null,
	public val expPositiveBuckets: List<ULong> = emptyList(),
	public val expNegativeOffset: Int? = null,
	public val expNegativeBuckets: List<ULong> = emptyList(),
	public val summaryCount: ULong? = null,
	public val summarySum: Double? = null,
	public val quantiles: List<Double> = emptyList(),
	public val quantileValues: List<Double> = emptyList(),
	/** OTLP `DataPointFlags` 그대로. */
	public val pointFlags: UInt = 0u,
) {
	init {
		require(envelope.signal == ObservationSignal.METRIC) { "metric point 의 signal 은 metric 이다" }
		seriesId?.let { requireHex64("series_id", it) }
		// 원본 oneof 는 하나만 채운다. 비유한 double 은 null + non_finite_value 다(ADR 0020 §4).
		require(valueInt == null || valueDouble == null) { "value_int 와 value_double 은 동시에 채우지 않는다" }
		require(valueDouble == null || valueDouble.isFinite()) { "value_double 은 유한값이다 — 비유한 값은 null 로 둔다" }
		require(quantiles.size == quantileValues.size) { "quantiles 와 quantile_values 는 길이가 같다" }
	}
}
