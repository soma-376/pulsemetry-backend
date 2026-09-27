package com.team376.pulsemetry.telemetry.adapter.observation

import com.team376.pulsemetry.telemetry.adapter.observation.profile.MetricPointView
import com.team376.pulsemetry.telemetry.adapter.observation.profile.MetricProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.ProductProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.ProfileRegistry
import com.team376.pulsemetry.telemetry.adapter.observation.profile.VersionSet
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.metrics.v1.AggregationTemporality
import io.opentelemetry.proto.metrics.v1.Exemplar
import io.opentelemetry.proto.metrics.v1.ExponentialHistogram
import io.opentelemetry.proto.metrics.v1.ExponentialHistogramDataPoint
import io.opentelemetry.proto.metrics.v1.Gauge
import io.opentelemetry.proto.metrics.v1.Histogram
import io.opentelemetry.proto.metrics.v1.HistogramDataPoint
import io.opentelemetry.proto.metrics.v1.Metric
import io.opentelemetry.proto.metrics.v1.NumberDataPoint
import io.opentelemetry.proto.metrics.v1.ResourceMetrics
import io.opentelemetry.proto.metrics.v1.ScopeMetrics
import io.opentelemetry.proto.metrics.v1.Sum
import io.opentelemetry.proto.metrics.v1.Summary
import io.opentelemetry.proto.metrics.v1.SummaryDataPoint
import io.opentelemetry.proto.resource.v1.Resource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * metric point 한 개 = 관측 한 개, 원형 보존과 구조 검증(ADR 0020 §4). 기대값은 OTLP 데이터 모델과 ADR 의 검증 조건에서 쓴다.
 */
class MetricPointObservationTest {

	private val t0 = 1_758_758_400_000_000_000L
	private val u64Max = -1L // ULong.MAX 의 fixed64 비트

	private fun kv(key: String, value: String) =
		KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setStringValue(value)).build()

	private fun request(metric: Metric) = ExportMetricsServiceRequest.newBuilder().addResourceMetrics(
		ResourceMetrics.newBuilder().setResource(Resource.newBuilder().addAttributes(kv("service.name", "claude-code")))
			.addScopeMetrics(ScopeMetrics.newBuilder().addMetrics(metric)),
	).build()

	private fun context() = ObservationContext("t", "i", EpochNanos(t0 + 1), "masking-v2", "stamp-v1", null)

	private fun point(metric: Metric, registry: ProfileRegistry = ProfileRegistry.EMPTY): MetricPointObservation =
		ObservationNormalizer(registry).normalize(request(metric), context()).metricPoints.single()

	private fun number() = NumberDataPoint.newBuilder().setTimeUnixNano(t0)
	private fun histogramPoint() = HistogramDataPoint.newBuilder().setTimeUnixNano(t0)

	private fun gauge(point: NumberDataPoint.Builder) = Metric.newBuilder().setName("g").setUnit("s").setGauge(Gauge.newBuilder().addDataPoints(point)).build()
	private fun histogram(point: HistogramDataPoint.Builder) = Metric.newBuilder().setName("h").setUnit("ms").setHistogram(
		Histogram.newBuilder().setAggregationTemporality(AggregationTemporality.AGGREGATION_TEMPORALITY_DELTA).addDataPoints(point),
	).build()

	// ── gauge · sum ─────────────────────────────────────────────────────────

	@Test
	@DisplayName("gauge — int/double oneof 하나만, temporality 는 none, monotonic 없음")
	fun gauge() {
		val integer = point(gauge(number().setAsInt(9_007_199_254_740_993)))
		val real = point(gauge(number().setAsDouble(0.25)))

		assertThat(integer.metricType).isEqualTo(MetricType.GAUGE)
		assertThat(integer.valueInt).isEqualTo(9_007_199_254_740_993)
		assertThat(integer.valueDouble).isNull()
		assertThat(integer.temporality).isEqualTo(Temporality.NONE)
		assertThat(integer.temporalityCode).isNull()
		assertThat(integer.isMonotonic).isNull()
		assertThat(real.valueDouble).isEqualTo(0.25)
		assertThat(real.valueInt).isNull()
	}

	@Test
	@DisplayName("sum — temporality·code·monotonic·시작 시각을 옮기고, unspecified 는 저장한다")
	fun sum() {
		fun sum(temporality: AggregationTemporality, monotonic: Boolean) = Metric.newBuilder().setName("s").setSum(
			Sum.newBuilder().setAggregationTemporality(temporality).setIsMonotonic(monotonic)
				.addDataPoints(number().setStartTimeUnixNano(t0 - 60).setAsInt(-3)),
		).build()

		val cumulative = point(sum(AggregationTemporality.AGGREGATION_TEMPORALITY_CUMULATIVE, false))
		val unspecified = point(sum(AggregationTemporality.AGGREGATION_TEMPORALITY_UNSPECIFIED, true))

		assertThat(cumulative.temporality).isEqualTo(Temporality.CUMULATIVE)
		assertThat(cumulative.temporalityCode).isEqualTo(2)
		assertThat(cumulative.isMonotonic).isFalse()
		assertThat(cumulative.valueInt).describedAs("non-monotonic sum 의 음수는 유효하다").isEqualTo(-3)
		assertThat(cumulative.startTime).isEqualTo(EpochNanos(t0 - 60))
		assertThat(unspecified.temporality).isEqualTo(Temporality.UNSPECIFIED)
		assertThat(unspecified.temporalityCode).isZero()
	}

	@Test
	@DisplayName("스칼라 NaN·±Infinity — value_double null + non_finite_value, 원래 값은 typed metadata 에만")
	fun nonFiniteScalars() {
		for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
			val observation = point(gauge(number().setAsDouble(value)))

			assertThat(observation.valueDouble).isNull()
			assertThat(observation.envelope.qualityFlags).contains(QualityFlag.NON_FINITE_VALUE)
			assertThat(TypedMetadata.fromJson(observation.envelope.metadataJson).record)
				.containsExactly(TypedAttribute("non_finite_value", TypedValue.Double(value)))
			assertThat(observation.envelope.attrs).doesNotContainKey("non_finite_value")
			assertThat(observation.structuralStatus).isEqualTo(StructuralStatus.VALID)
		}
	}

	// ── histogram ───────────────────────────────────────────────────────────

	@Test
	@DisplayName("histogram — count·sum·min·max·bounds·buckets 를 원형대로, hist_buckets_present true")
	fun validHistogram() {
		val observation = point(
			histogram(histogramPoint().setCount(6).setSum(20.0).setMin(0.5).setMax(9.0).addAllExplicitBounds(listOf(1.0, 5.0)).addAllBucketCounts(listOf(1, 2, 3))),
		)

		assertThat(observation.structuralStatus).isEqualTo(StructuralStatus.VALID)
		assertThat(observation.histCount).isEqualTo(6uL)
		assertThat(observation.histSum).isEqualTo(20.0)
		assertThat(observation.histMin).isEqualTo(0.5)
		assertThat(observation.histMax).isEqualTo(9.0)
		assertThat(observation.explicitBounds).containsExactly(1.0, 5.0)
		assertThat(observation.bucketCounts).containsExactly(1uL, 2uL, 3uL)
		assertThat(observation.histBucketsPresent).isTrue()
		assertThat(observation.temporality).isEqualTo(Temporality.DELTA)
	}

	@Test
	@DisplayName("bounds·buckets 모두 생략은 유효하다 — count·sum 은 남고 분포는 없다(hist_buckets_present false), 없는 min·max 는 null")
	fun histogramWithoutBuckets() {
		val observation = point(histogram(histogramPoint().setCount(3).setSum(12.0)))

		assertThat(observation.structuralStatus).isEqualTo(StructuralStatus.VALID)
		assertThat(observation.histCount).isEqualTo(3uL)
		assertThat(observation.histSum).isEqualTo(12.0)
		assertThat(observation.histMin).isNull()
		assertThat(observation.histBucketsPresent).isFalse()
		assertThat(observation.bucketCounts).isEmpty()
	}

	@Test
	@DisplayName("구조 불량 — buckets 수 ≠ bounds+1, bounds 비증가, bucket 합 ≠ count, bucket 합 overflow: 전부 비우고 invalid")
	fun malformedHistograms() {
		val cases = mapOf(
			"bucket 수" to histogramPoint().setCount(3).addAllExplicitBounds(listOf(1.0, 5.0)).addAllBucketCounts(listOf(1, 2)),
			"bounds 없이 buckets 둘" to histogramPoint().setCount(3).addAllBucketCounts(listOf(1, 2)),
			"bounds 만" to histogramPoint().setCount(0).addAllExplicitBounds(listOf(1.0)),
			"같은 bound" to histogramPoint().setCount(3).addAllExplicitBounds(listOf(1.0, 1.0)).addAllBucketCounts(listOf(1, 1, 1)),
			"감소 bound" to histogramPoint().setCount(3).addAllExplicitBounds(listOf(5.0, 1.0)).addAllBucketCounts(listOf(1, 1, 1)),
			"합 ≠ count" to histogramPoint().setCount(7).addAllExplicitBounds(listOf(1.0)).addAllBucketCounts(listOf(1, 2)),
			"합 overflow" to histogramPoint().setCount(u64Max).addAllExplicitBounds(listOf(1.0)).addAllBucketCounts(listOf(u64Max, 1)),
		)

		cases.forEach { (why, builder) ->
			val observation = point(histogram(builder.setSum(1.0)))
			assertThat(observation.structuralStatus).describedAs(why).isEqualTo(StructuralStatus.INVALID)
			assertThat(observation.histCount).describedAs(why).isNull()
			assertThat(observation.histSum).describedAs(why).isNull()
			assertThat(observation.explicitBounds).describedAs(why).isEmpty()
			assertThat(observation.bucketCounts).describedAs(why).isEmpty()
			assertThat(observation.histBucketsPresent).describedAs(why).isFalse()
			assertThat(observation.envelope.qualityFlags).describedAs(why).doesNotContain(QualityFlag.NON_FINITE_VALUE)
		}
	}

	@Test
	@DisplayName("구조 안의 비유한 값(sum·min·max·bound) — 비우고 invalid + non_finite_value")
	fun nonFiniteInsideHistogram() {
		val cases = listOf(
			histogramPoint().setCount(1).setSum(Double.NaN),
			histogramPoint().setCount(1).setMin(Double.NEGATIVE_INFINITY),
			histogramPoint().setCount(1).addAllExplicitBounds(listOf(Double.POSITIVE_INFINITY)).addAllBucketCounts(listOf(1, 0)),
		)

		cases.forEach { builder ->
			val observation = point(histogram(builder))
			assertThat(observation.structuralStatus).isEqualTo(StructuralStatus.INVALID)
			assertThat(observation.envelope.qualityFlags).contains(QualityFlag.NON_FINITE_VALUE)
			assertThat(observation.histCount).isNull()
		}
	}

	@Test
	@DisplayName("UInt64 최대 count·bucket 을 부호 없이 보존한다")
	fun unsigned64() {
		val observation = point(histogram(histogramPoint().setCount(u64Max).addAllBucketCounts(listOf(u64Max))))

		assertThat(observation.structuralStatus).isEqualTo(StructuralStatus.VALID)
		assertThat(observation.histCount).isEqualTo(ULong.MAX_VALUE)
		assertThat(observation.bucketCounts).containsExactly(ULong.MAX_VALUE)
	}

	// ── exponential histogram ───────────────────────────────────────────────

	@Test
	@DisplayName("exponential histogram — scale·zero count/threshold·양·음 offset 과 buckets")
	fun exponentialHistogram() {
		val metric = Metric.newBuilder().setName("e").setExponentialHistogram(
			ExponentialHistogram.newBuilder().setAggregationTemporality(AggregationTemporality.AGGREGATION_TEMPORALITY_CUMULATIVE).addDataPoints(
				ExponentialHistogramDataPoint.newBuilder().setTimeUnixNano(t0).setCount(u64Max).setSum(3.5).setScale(-2).setZeroCount(4).setZeroThreshold(0.001)
					.setPositive(ExponentialHistogramDataPoint.Buckets.newBuilder().setOffset(-3).addAllBucketCounts(listOf(1, u64Max)))
					.setNegative(ExponentialHistogramDataPoint.Buckets.newBuilder().setOffset(2).addBucketCounts(7)),
			),
		).build()

		val observation = point(metric)

		assertThat(observation.metricType).isEqualTo(MetricType.EXPONENTIAL_HISTOGRAM)
		assertThat(observation.temporality).isEqualTo(Temporality.CUMULATIVE)
		assertThat(observation.histCount).isEqualTo(ULong.MAX_VALUE)
		assertThat(observation.histSum).isEqualTo(3.5)
		assertThat(observation.expScale).isEqualTo(-2)
		assertThat(observation.expZeroCount).isEqualTo(4uL)
		assertThat(observation.expZeroThreshold).isEqualTo(0.001)
		assertThat(observation.expPositiveOffset).isEqualTo(-3)
		assertThat(observation.expPositiveBuckets).containsExactly(1uL, ULong.MAX_VALUE)
		assertThat(observation.expNegativeOffset).isEqualTo(2)
		assertThat(observation.expNegativeBuckets).containsExactly(7uL)
		assertThat(observation.explicitBounds).isEmpty()
		assertThat(observation.histBucketsPresent).describedAs("explicit histogram 의 표시다").isFalse()
	}

	@Test
	@DisplayName("exponential histogram 의 비유한 zero threshold — 비우고 invalid + non_finite_value")
	fun nonFiniteExponential() {
		val metric = Metric.newBuilder().setName("e").setExponentialHistogram(
			ExponentialHistogram.newBuilder().addDataPoints(
				ExponentialHistogramDataPoint.newBuilder().setTimeUnixNano(t0).setCount(1).setZeroThreshold(Double.NaN)
					.setPositive(ExponentialHistogramDataPoint.Buckets.newBuilder().addBucketCounts(1)),
			),
		).build()

		val observation = point(metric)

		assertThat(observation.structuralStatus).isEqualTo(StructuralStatus.INVALID)
		assertThat(observation.envelope.qualityFlags).contains(QualityFlag.NON_FINITE_VALUE)
		assertThat(observation.expPositiveBuckets).isEmpty()
		assertThat(observation.expScale).isNull()
	}

	// ── summary ─────────────────────────────────────────────────────────────

	private fun summary(vararg quantiles: Pair<Double, Double>, sum: Double = 10.0) =
		Metric.newBuilder().setName("q").setSummary(
			Summary.newBuilder().addDataPoints(
				SummaryDataPoint.newBuilder().setTimeUnixNano(t0).setCount(4).setSum(sum).addAllQuantileValues(
					quantiles.map { (q, v) -> SummaryDataPoint.ValueAtQuantile.newBuilder().setQuantile(q).setValue(v).build() },
				),
			),
		).build()

	@Test
	@DisplayName("summary — count·sum 과 같은 길이의 quantile·value, temporality none")
	fun validSummary() {
		val observation = point(summary(0.0 to 1.0, 0.5 to 2.5, 1.0 to 9.0))

		assertThat(observation.summaryCount).isEqualTo(4uL)
		assertThat(observation.summarySum).isEqualTo(10.0)
		assertThat(observation.quantiles).containsExactly(0.0, 0.5, 1.0)
		assertThat(observation.quantileValues).containsExactly(1.0, 2.5, 9.0)
		assertThat(observation.temporality).isEqualTo(Temporality.NONE)
	}

	@Test
	@DisplayName("summary 불량 — quantile 이 [0, 1] 밖이면 invalid, 비유한 값이면 invalid + non_finite_value")
	fun invalidSummaries() {
		val outside = point(summary(1.5 to 2.0))
		val nonFinite = point(summary(0.5 to Double.NaN))

		assertThat(outside.structuralStatus).isEqualTo(StructuralStatus.INVALID)
		assertThat(outside.summaryCount).isNull()
		assertThat(outside.quantiles).isEmpty()
		assertThat(outside.envelope.qualityFlags).doesNotContain(QualityFlag.NON_FINITE_VALUE)
		assertThat(nonFinite.structuralStatus).isEqualTo(StructuralStatus.INVALID)
		assertThat(nonFinite.envelope.qualityFlags).contains(QualityFlag.NON_FINITE_VALUE)
	}

	// ── flags · exemplar · 역할 ─────────────────────────────────────────────

	@Test
	@DisplayName("no-recorded-value — flag 를 그대로 두고 수치를 싣지 않는다(0 으로 채우지 않는다)")
	fun noRecordedValue() {
		val scalar = point(gauge(number().setAsInt(0).setFlags(1)))
		val distribution = point(histogram(histogramPoint().setFlags(1).setCount(0).addAllBucketCounts(listOf(0, 0))))

		assertThat(scalar.pointFlags).isEqualTo(1u)
		assertThat(scalar.valueInt).isNull()
		assertThat(distribution.pointFlags).isEqualTo(1u)
		assertThat(distribution.histCount).isNull()
		assertThat(distribution.structuralStatus).describedAs("측정이 없으므로 구조 검증 대상이 아니다").isEqualTo(StructuralStatus.VALID)
		assertThat(point(gauge(number().setAsInt(0))).valueInt).describedAs("flag 가 없으면 0 은 측정이다").isZero()
	}

	@Test
	@DisplayName("exemplar 의 허용 속성만 metadata 에 — 허용되지 않은 것은 남지 않는다")
	fun exemplarAllowlist() {
		val metric = gauge(
			number().setAsInt(1).addAttributes(kv("type", "input")).addAttributes(kv("user", "u-1"))
				.addExemplars(Exemplar.newBuilder().setAsInt(1).addFilteredAttributes(kv("allowed.dim", "a")).addFilteredAttributes(kv("session", "s-1"))),
		)

		val observation = point(metric, ProfileRegistry(listOf(TestClaudeMetrics(UsageRole.DIAGNOSTIC))))
		val metadata = TypedMetadata.fromJson(observation.envelope.metadataJson)

		assertThat(metadata.record).containsExactly(TypedAttribute("type", TypedValue.Str("input")))
		assertThat(metadata.exemplars).containsExactly(listOf(TypedAttribute("allowed.dim", TypedValue.Str("a"))))
		assertThat(observation.envelope.usageRole).isEqualTo(UsageRole.DIAGNOSTIC)
		assertThat(observation.metricFamily).isEqualTo(MetricFamily.TOKEN_USAGE)
	}

	@Test
	@DisplayName("metric point 는 primary 가 될 수 없다 — 프로파일이 그렇게 매핑하면 실패한다")
	fun metricsAreNeverPrimary() {
		assertThatThrownBy { point(gauge(number().setAsInt(1)), ProfileRegistry(listOf(TestClaudeMetrics(UsageRole.PRIMARY)))) }
			.isInstanceOf(IllegalStateException::class.java)
	}

	/** 테스트 전용 — 모든 metric 을 token.usage 로 매핑한다. 실제 Claude 매핑이 아니다. */
	private class TestClaudeMetrics(role: UsageRole) : ProductProfile {
		override val product = Product.CLAUDE_CODE
		override val versions = VersionSet.ANY
		override val mappingVersion = "test-claude-v1"
		override val logs = null
		override val spans = null
		override val metrics = object : MetricProfile {
			override val allowlist = MetadataAllowlist("test-v1", record = setOf("type"), exemplar = setOf("allowed.dim"))
			override fun map(view: MetricPointView, base: MetricPointObservation) = base.copy(
				envelope = base.envelope.copy(mappingStatus = MappingStatus.MAPPED, usageRole = role),
				metricFamily = MetricFamily.TOKEN_USAGE,
			)
		}
	}
}
