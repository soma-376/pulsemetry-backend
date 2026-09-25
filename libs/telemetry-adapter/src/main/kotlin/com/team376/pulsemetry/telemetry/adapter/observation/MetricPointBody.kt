package com.team376.pulsemetry.telemetry.adapter.observation

import com.google.protobuf.Message
import com.google.protobuf.MessageOrBuilder

/**
 * metric point 하나의 본문을 원형 그대로 읽고 구조를 검증한다(ADR 0020 §4).
 *
 * 다섯 wire 형식의 필드를 옮기고, histogram·summary 를 스칼라로 납작하게 만들지 않는다. 정수는 `Long`,
 * 실수는 `Double`, count·bucket 은 부호 없는 64비트다. 구간 집계는 이 단계의 일이 아니다.
 *
 * ## 스칼라(gauge·sum)
 *
 * double 이 NaN/±Infinity 면 `value_double = null` + `non_finite_value` 이고, 원래 값은 typed metadata 의 레코드
 * 경계에 `non_finite_value` 속성으로 남는다([annotations]).
 *
 * ## 분포(histogram·exponential histogram·summary)
 *
 * 구조가 성립하지 않으면 **그 종류의 typed 구조를 통째로 비우고** `structural_status = invalid` 로 둔다 — 원소 하나를
 * 지워 유효한 분포처럼 만들지 않는다. 원형은 아카이브 참조로 추적한다. 성립 조건:
 *
 * - histogram: bounds 와 buckets 가 **모두 생략**됐거나(유효 — 분포 집계만 불가, `hist_buckets_present = false`),
 *   bounds 가 엄격 증가·buckets 수 = bounds 수 + 1·bucket 합 = count. sum·min·max·bounds 가 유한값.
 * - exponential histogram: sum·min·max·zero threshold 가 유한값. bucket 합 검증은 하지 않는다(ADR 0020 이 요구하지 않는다).
 * - summary: sum·quantile·quantile value 가 유한값, quantile 이 [0, 1] 안.
 *
 * 비유한 값이 원인이면 `non_finite_value` 도 남긴다.
 *
 * ## no-recorded-value
 *
 * `point_flags` 의 no-recorded-value 비트가 켜진 point 는 측정이 없다는 표시다. flag 를 그대로 두고 **수치를 싣지 않는다**
 * (0 으로 채우지 않는다) — 구조 검증 대상도 아니다.
 */
internal class MetricPointBody private constructor(
	val metricType: MetricType,
	val point: MessageOrBuilder,
	private val body: MessageOrBuilder,
	/** 봉투에 더할 품질 플래그. */
	val flags: Set<QualityFlag>,
	/** metadata 레코드 경계에 더할 속성(원래 비유한 값). */
	val annotations: List<TypedAttribute>,
	private val structuralStatus: StructuralStatus,
	private val noRecordedValue: Boolean,
) {

	fun toObservation(
		envelope: ObservationEnvelope,
		name: String,
		description: String,
		unit: String,
		series: SeriesIdentity,
	): MetricPointObservation {
		val temporalityCode = if (body.descriptorForType.findFieldByName("aggregation_temporality") != null) {
			Otlp.enumNumber(body, "aggregation_temporality")
		} else {
			null
		}
		val common = MetricPointObservation(
			envelope = envelope,
			seriesId = series.seriesId,
			seriesIdentityStatus = series.status,
			structuralStatus = structuralStatus,
			metricName = name,
			metricType = metricType,
			description = description.ifEmpty { null },
			rawUnit = unit,
			temporality = temporalityOf(temporalityCode),
			temporalityCode = temporalityCode,
			isMonotonic = if (metricType == MetricType.SUM) body.getField(body.descriptorForType.findFieldByName("is_monotonic")) as Boolean else null,
			startTime = OtlpTime.optional(OtlpTime.ofBits(Otlp.long(point, "start_time_unix_nano"))),
			pointFlags = Otlp.int(point, "flags").toUInt(),
		)
		if (noRecordedValue || structuralStatus == StructuralStatus.INVALID) return common
		return when (metricType) {
			MetricType.GAUGE, MetricType.SUM -> number(common)
			MetricType.HISTOGRAM -> {
				val bounds = doubles(point, "explicit_bounds")
				val buckets = unsignedList(point, "bucket_counts")
				common.copy(
					histCount = Otlp.long(point, "count").toULong(),
					histSum = optionalDouble(point, "sum"),
					histMin = optionalDouble(point, "min"),
					histMax = optionalDouble(point, "max"),
					explicitBounds = bounds,
					bucketCounts = buckets,
					histBucketsPresent = bounds.isNotEmpty() || buckets.isNotEmpty(),
				)
			}
			MetricType.EXPONENTIAL_HISTOGRAM -> {
				val positive = Otlp.message(point, "positive")
				val negative = Otlp.message(point, "negative")
				common.copy(
					histCount = Otlp.long(point, "count").toULong(),
					histSum = optionalDouble(point, "sum"),
					histMin = optionalDouble(point, "min"),
					histMax = optionalDouble(point, "max"),
					expScale = Otlp.int(point, "scale"),
					expZeroCount = Otlp.long(point, "zero_count").toULong(),
					expZeroThreshold = Otlp.double(point, "zero_threshold"),
					expPositiveOffset = positive?.let { Otlp.int(it, "offset") },
					expPositiveBuckets = positive?.let { unsignedList(it, "bucket_counts") } ?: emptyList(),
					expNegativeOffset = negative?.let { Otlp.int(it, "offset") },
					expNegativeBuckets = negative?.let { unsignedList(it, "bucket_counts") } ?: emptyList(),
				)
			}
			MetricType.SUMMARY -> {
				val quantiles = Otlp.repeated(point, "quantile_values")
				common.copy(
					summaryCount = Otlp.long(point, "count").toULong(),
					summarySum = Otlp.double(point, "sum"),
					quantiles = quantiles.map { Otlp.double(it, "quantile") },
					quantileValues = quantiles.map { Otlp.double(it, "value") },
				)
			}
		}
	}

	private fun number(common: MetricPointObservation): MetricPointObservation =
		when (val value = numberValue(point)) {
			is Long -> common.copy(valueInt = value)
			is Double -> common.copy(valueDouble = value.takeIf { it.isFinite() })
			else -> common
		}

	private fun temporalityOf(code: Int?): Temporality = when (code) {
		null -> Temporality.NONE
		1 -> Temporality.DELTA
		2 -> Temporality.CUMULATIVE
		else -> Temporality.UNSPECIFIED
	}

	companion object {
		/** OTLP `DataPointFlags.FLAG_NO_RECORDED_VALUE`. */
		const val FLAG_NO_RECORDED_VALUE: Int = 1

		/** 원래 비유한 값을 담는 metadata 속성 이름. */
		const val NON_FINITE_VALUE_KEY: String = "non_finite_value"

		fun read(metric: Message, index: Int): MetricPointBody {
			val data = requireNotNull(MetricPoints.dataField(metric)) { "데이터가 없는 metric 이다" }
			val body = metric.getField(data) as MessageOrBuilder
			val point = Otlp.repeated(body, "data_points")[index]
			val type = when (data.name) {
				"gauge" -> MetricType.GAUGE
				"sum" -> MetricType.SUM
				"histogram" -> MetricType.HISTOGRAM
				"exponential_histogram" -> MetricType.EXPONENTIAL_HISTOGRAM
				"summary" -> MetricType.SUMMARY
				else -> error("모르는 metric 종류: ${data.name}")
			}
			val noRecordedValue = Otlp.int(point, "flags") and FLAG_NO_RECORDED_VALUE != 0
			if (noRecordedValue) return MetricPointBody(type, point, body, emptySet(), emptyList(), StructuralStatus.VALID, true)

			return when (type) {
				MetricType.GAUGE, MetricType.SUM -> {
					val value = numberValue(point)
					if (value is Double && !value.isFinite()) {
						MetricPointBody(
							type, point, body, setOf(QualityFlag.NON_FINITE_VALUE),
							listOf(TypedAttribute(NON_FINITE_VALUE_KEY, TypedValue.Double(value))), StructuralStatus.VALID, false,
						)
					} else {
						MetricPointBody(type, point, body, emptySet(), emptyList(), StructuralStatus.VALID, false)
					}
				}
				MetricType.HISTOGRAM -> distribution(type, point, body, histogramCheck(point))
				MetricType.EXPONENTIAL_HISTOGRAM -> distribution(type, point, body, exponentialCheck(point))
				MetricType.SUMMARY -> distribution(type, point, body, summaryCheck(point))
			}
		}

		private fun distribution(type: MetricType, point: MessageOrBuilder, body: MessageOrBuilder, check: Check): MetricPointBody =
			when (check) {
				Check.VALID -> MetricPointBody(type, point, body, emptySet(), emptyList(), StructuralStatus.VALID, false)
				Check.MALFORMED -> MetricPointBody(type, point, body, emptySet(), emptyList(), StructuralStatus.INVALID, false)
				Check.NON_FINITE -> MetricPointBody(type, point, body, setOf(QualityFlag.NON_FINITE_VALUE), emptyList(), StructuralStatus.INVALID, false)
			}

		private enum class Check { VALID, MALFORMED, NON_FINITE }

		private fun histogramCheck(point: MessageOrBuilder): Check {
			val bounds = doubles(point, "explicit_bounds")
			if ((listOfNotNull(optionalDouble(point, "sum"), optionalDouble(point, "min"), optionalDouble(point, "max")) + bounds)
					.any { !it.isFinite() }
			) {
				return Check.NON_FINITE
			}
			val buckets = unsignedList(point, "bucket_counts")
			if (bounds.isEmpty() && buckets.isEmpty()) return Check.VALID
			if (buckets.size != bounds.size + 1) return Check.MALFORMED
			if (bounds.zipWithNext().any { (a, b) -> a >= b }) return Check.MALFORMED
			val total = sumOrNull(buckets) ?: return Check.MALFORMED
			return if (total == Otlp.long(point, "count").toULong()) Check.VALID else Check.MALFORMED
		}

		private fun exponentialCheck(point: MessageOrBuilder): Check {
			val values = listOfNotNull(
				optionalDouble(point, "sum"), optionalDouble(point, "min"), optionalDouble(point, "max"), Otlp.double(point, "zero_threshold"),
			)
			return if (values.any { !it.isFinite() }) Check.NON_FINITE else Check.VALID
		}

		private fun summaryCheck(point: MessageOrBuilder): Check {
			val quantiles = Otlp.repeated(point, "quantile_values")
			val numbers = listOf(Otlp.double(point, "sum")) + quantiles.flatMap { listOf(Otlp.double(it, "quantile"), Otlp.double(it, "value")) }
			if (numbers.any { !it.isFinite() }) return Check.NON_FINITE
			return if (quantiles.all { Otlp.double(it, "quantile") in 0.0..1.0 }) Check.VALID else Check.MALFORMED
		}

		/** 부호 없는 합. 넘치면 null — 구조가 성립하지 않는다. */
		private fun sumOrNull(values: List<ULong>): ULong? {
			var total = 0uL
			for (value in values) {
				if (total > ULong.MAX_VALUE - value) return null
				total += value
			}
			return total
		}

		/** number point 의 oneof 값 — `Long`, `Double`, 또는 설정되지 않았으면 null. */
		private fun numberValue(point: MessageOrBuilder): Any? {
			val oneof = point.descriptorForType.oneofs.firstOrNull { it.name == "value" } ?: return null
			val field = point.getOneofFieldDescriptor(oneof) ?: return null
			return point.getField(field)
		}

		@Suppress("UNCHECKED_CAST")
		private fun unsignedList(message: MessageOrBuilder, field: String): List<ULong> =
			(message.getField(message.descriptorForType.findFieldByName(field)) as List<Long>).map { it.toULong() }

		@Suppress("UNCHECKED_CAST")
		private fun doubles(message: MessageOrBuilder, field: String): List<Double> =
			message.getField(message.descriptorForType.findFieldByName(field)) as List<Double>

		private fun optionalDouble(message: MessageOrBuilder, field: String): Double? =
			if (Otlp.has(message, field)) Otlp.double(message, field) else null
	}
}
