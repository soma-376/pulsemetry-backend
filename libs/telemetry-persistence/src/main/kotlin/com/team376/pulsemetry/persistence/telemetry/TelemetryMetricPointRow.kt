package com.team376.pulsemetry.persistence.telemetry

import com.team376.pulsemetry.telemetry.adapter.observation.RowVersioning
import com.team376.pulsemetry.telemetry.enricher.observation.EnrichedMetricPoint

/**
 * `telemetry_metric_points` 한 행(85컬럼)의 JSONEachRow 표기(ADR 0020 §1·§4). 봉투 49 뒤에 point 고유 36컬럼을 DDL 순서로
 * 쓴다. count·bucket 은 UInt64 전 범위, Float64 는 문자열이다([AnalysisInsert] 가 정밀 파싱한다). 해당 없는 배열은 빈
 * 배열, 선택 수치는 null 이다.
 */
internal object TelemetryMetricPointRow {

	fun toJson(point: EnrichedMetricPoint, versioning: RowVersioning): String {
		val o = point.observation
		val w = AnalysisRowWriter(AnalysisColumns.METRIC_POINTS)
		AnalysisEnvelopeRow.write(w, o.envelope, point.org, versioning)
		w.hex64("series_id", o.seriesId)
		w.lowCardinality("series_identity_status", o.seriesIdentityStatus)
		w.lowCardinality("structural_status", o.structuralStatus)
		w.string("metric_name", o.metricName)
		w.lowCardinality("metric_type", o.metricType)
		w.lowCardinality("metric_family", o.metricFamily)
		w.string("metric_profile", o.metricProfile)
		w.string("description", o.description)
		w.string("raw_unit", o.rawUnit)
		w.lowCardinality("canonical_unit", o.canonicalUnit)
		w.lowCardinality("token_component", o.tokenComponent)
		w.lowCardinality("temporality", o.temporality)
		w.int32("temporality_code", o.temporalityCode)
		w.bool("is_monotonic", o.isMonotonic)
		w.time("start_time", o.startTime)
		w.int64("value_int", o.valueInt)
		w.float64("value_double", o.valueDouble)
		w.uint64("hist_count", o.histCount)
		w.float64("hist_sum", o.histSum)
		w.float64("hist_min", o.histMin)
		w.float64("hist_max", o.histMax)
		w.bool("hist_buckets_present", o.histBucketsPresent)
		w.float64Array("explicit_bounds", o.explicitBounds)
		w.uint64Array("bucket_counts", o.bucketCounts)
		w.int32("exp_scale", o.expScale)
		w.uint64("exp_zero_count", o.expZeroCount)
		w.float64("exp_zero_threshold", o.expZeroThreshold)
		w.int32("exp_positive_offset", o.expPositiveOffset)
		w.uint64Array("exp_positive_buckets", o.expPositiveBuckets)
		w.int32("exp_negative_offset", o.expNegativeOffset)
		w.uint64Array("exp_negative_buckets", o.expNegativeBuckets)
		w.uint64("summary_count", o.summaryCount)
		w.float64("summary_sum", o.summarySum)
		w.float64Array("quantiles", o.quantiles)
		w.float64Array("quantile_values", o.quantileValues)
		w.uint32("point_flags", o.pointFlags)
		return w.build()
	}
}
