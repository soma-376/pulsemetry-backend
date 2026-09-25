package com.team376.pulsemetry.telemetry.adapter.observation

import com.google.protobuf.Message
import com.google.protobuf.MessageOrBuilder

/**
 * metric point 하나의 본문을 원형 그대로 읽는다(ADR 0020 §4). 다섯 wire 형식의 필드를 옮기고, histogram·summary 를
 * 스칼라로 납작하게 만들지 않는다. 정수는 `Long`, 실수는 `Double`, count·bucket 은 부호 없는 64비트다.
 *
 * 스칼라 double 이 비유한 값이면 `value_double = null` + `non_finite_value` 다.
 */
internal class MetricPointBody private constructor(
	val metricType: MetricType,
	val point: MessageOrBuilder,
	private val body: MessageOrBuilder,
	val flags: Set<QualityFlag>,
) {

	fun toObservation(
		envelope: ObservationEnvelope,
		name: String,
		description: String,
		unit: String,
		series: SeriesIdentity,
	): MetricPointObservation {
		val temporalityCode = if (Otlp.has(body, "aggregation_temporality") || hasTemporality()) Otlp.enumNumber(body, "aggregation_temporality") else null
		val common = MetricPointObservation(
			envelope = envelope,
			seriesId = series.seriesId,
			seriesIdentityStatus = series.status,
			structuralStatus = StructuralStatus.VALID,
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
		return when (metricType) {
			MetricType.GAUGE, MetricType.SUM -> number(common)
			MetricType.HISTOGRAM -> common.copy(
				histCount = unsigned(Otlp.long(point, "count")),
				histSum = optionalDouble(point, "sum"),
				histMin = optionalDouble(point, "min"),
				histMax = optionalDouble(point, "max"),
				explicitBounds = doubles(point, "explicit_bounds"),
				bucketCounts = unsignedList(point, "bucket_counts"),
				histBucketsPresent = doubles(point, "explicit_bounds").isNotEmpty() || unsignedList(point, "bucket_counts").isNotEmpty(),
			)
			MetricType.EXPONENTIAL_HISTOGRAM -> {
				val positive = Otlp.message(point, "positive")
				val negative = Otlp.message(point, "negative")
				common.copy(
					histCount = unsigned(Otlp.long(point, "count")),
					histSum = optionalDouble(point, "sum"),
					histMin = optionalDouble(point, "min"),
					histMax = optionalDouble(point, "max"),
					expScale = Otlp.int(point, "scale"),
					expZeroCount = unsigned(Otlp.long(point, "zero_count")),
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
					summaryCount = unsigned(Otlp.long(point, "count")),
					summarySum = Otlp.double(point, "sum"),
					quantiles = quantiles.map { Otlp.double(it, "quantile") },
					quantileValues = quantiles.map { Otlp.double(it, "value") },
				)
			}
		}
	}

	private fun hasTemporality(): Boolean = body.descriptorForType.findFieldByName("aggregation_temporality") != null

	private fun number(common: MetricPointObservation): MetricPointObservation {
		val oneof = point.descriptorForType.oneofs.firstOrNull { it.name == "value" }
		val field = oneof?.let { point.getOneofFieldDescriptor(it) }
		return when (field?.name) {
			"as_int" -> common.copy(valueInt = point.getField(field) as Long)
			"as_double" -> common.copy(valueDouble = (point.getField(field) as Double).takeIf { it.isFinite() })
			else -> common
		}
	}

	private fun temporalityOf(code: Int?): Temporality = when (code) {
		null -> Temporality.NONE
		1 -> Temporality.DELTA
		2 -> Temporality.CUMULATIVE
		else -> Temporality.UNSPECIFIED
	}

	companion object {
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
			val nonFinite = (type == MetricType.GAUGE || type == MetricType.SUM) && Otlp.has(point, "as_double") &&
				!(point.getField(point.descriptorForType.findFieldByName("as_double")) as Double).isFinite()
			return MetricPointBody(type, point, body, if (nonFinite) setOf(QualityFlag.NON_FINITE_VALUE) else emptySet())
		}

		private fun unsigned(bits: Long): ULong = bits.toULong()

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
