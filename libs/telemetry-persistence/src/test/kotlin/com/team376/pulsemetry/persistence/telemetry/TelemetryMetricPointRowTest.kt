package com.team376.pulsemetry.persistence.telemetry

import com.team376.pulsemetry.persistence.telemetry.AnalysisSamples.metricPoint
import com.team376.pulsemetry.persistence.telemetry.AnalysisSamples.metricPointObservation
import com.team376.pulsemetry.telemetry.adapter.observation.MetricPointObservation
import com.team376.pulsemetry.telemetry.adapter.observation.MetricType
import com.team376.pulsemetry.telemetry.adapter.observation.RowVersioning
import com.team376.pulsemetry.telemetry.adapter.observation.SeriesIdentityStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `telemetry_metric_points` 행 인코더(ADR 0020 §1·§4). count·bucket 은 UInt64 전 범위, 음수 gauge 는 Int64, Float64 는 가장
 * 짧은 왕복 표기의 문자열, 해당 없는 분포 배열은 빈 배열이다.
 */
class TelemetryMetricPointRowTest {

	private val versioning = RowVersioning.of(1u, 7u)

	private fun tokens(observation: MetricPointObservation) = RowTokens.parse(TelemetryMetricPointRow.toJson(metricPoint(observation), versioning))

	@Test
	@DisplayName("85컬럼을 DDL 순서대로 전부 쓴다 — 해당 없는 분포는 null·빈 배열·false")
	fun writesEveryColumnInOrder() {
		val row = tokens(metricPointObservation())

		assertThat(row.keys).containsExactlyElementsOf(AnalysisColumns.METRIC_POINTS.map { it.name })
		assertThat(row["series_id"]).isEqualTo("null")
		assertThat(row["value_int"]).isEqualTo("null")
		assertThat(row["value_double"]).isEqualTo("null")
		assertThat(row["hist_count"]).isEqualTo("null")
		assertThat(row["hist_buckets_present"]).isEqualTo("false")
		assertThat(row["bucket_counts"]).isEqualTo("[]")
		assertThat(row["quantiles"]).isEqualTo("[]")
		assertThat(row["point_flags"]).isEqualTo("0")
		assertThat(row["metric_family"]).isEqualTo("\"vendor.metric\"")
	}

	@Test
	@DisplayName("음수 gauge 는 Int64 그대로, point flag 는 UInt32 그대로다")
	fun signedGaugeAndFlags() {
		val row = tokens(metricPointObservation().copy(metricType = MetricType.GAUGE, valueInt = Long.MIN_VALUE, pointFlags = 1u))

		assertThat(row["value_int"]).isEqualTo("-9223372036854775808")
		assertThat(row["point_flags"]).isEqualTo("1")
	}

	@Test
	@DisplayName("histogram 의 count·bucket 은 UInt64 전 범위를 Double 없이 쓴다")
	fun histogramCountsAreUnsigned64() {
		val row = tokens(
			metricPointObservation().copy(
				metricType = MetricType.HISTOGRAM,
				histCount = ULong.MAX_VALUE,
				histSum = 0.1 + 0.2,
				histBucketsPresent = true,
				explicitBounds = listOf(1.0E-7, 10.0),
				bucketCounts = listOf(ULong.MAX_VALUE, 9_007_199_254_740_993uL, 0uL),
			),
		)

		assertThat(row["hist_count"]).isEqualTo("18446744073709551615")
		assertThat(row["bucket_counts"]).isEqualTo("[18446744073709551615,9007199254740993,0]")
		assertThat(row["hist_sum"]).isEqualTo("\"0.30000000000000004\"")
		assertThat(row["explicit_bounds"]).isEqualTo("[\"1.0E-7\",\"10.0\"]")
		assertThat(row["hist_buckets_present"]).isEqualTo("true")
	}

	@Test
	@DisplayName("series_id 는 64자리 소문자 hex 만 적재한다")
	fun seriesIdIsHex64() {
		assertThat(tokens(metricPointObservation().copy(seriesId = "d".repeat(64), seriesIdentityStatus = SeriesIdentityStatus.VERIFIED))["series_id"])
			.isEqualTo("\"${"d".repeat(64)}\"")
		assertThatThrownBy { AnalysisRowWriter(listOf(ColumnSpec("series_id", "Nullable(FixedString(64))"))).hex64("series_id", "d".repeat(63)) }
			.isInstanceOf(TelemetrySinkRejectedException::class.java)
	}
}
