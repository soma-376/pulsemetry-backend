package com.team376.pulsemetry.persistence.telemetry

import com.team376.pulsemetry.telemetry.adapter.observation.EpochNanos
import com.team376.pulsemetry.telemetry.adapter.observation.Hex64
import com.team376.pulsemetry.telemetry.adapter.observation.Money
import com.team376.pulsemetry.telemetry.adapter.observation.WireValue
import java.math.BigDecimal
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 분석 테이블 행 하나를 JSONEachRow 한 줄로 쓴다(ADR 0020 §1 직렬화). **모든 컬럼을 선언 순서대로 명시한다** — 컬럼마다
 * [ColumnSpec] 과 이름·기반 타입·null 가능성을 대조하고, 빠진 컬럼이 있으면 [build] 가 거부한다. ClickHouse 의 누락 컬럼
 * 기본값에 기대지 않는다.
 *
 * 값의 표기는 타입마다 정해져 있다.
 * - `Int64`·`UInt64`·`UInt32` 는 Double 을 거치지 않는 정수 토큰이다.
 * - `Decimal(38, 12)` 는 지수 없는 정확한 10진 토큰이다. 절삭하지 않는다 — 들어가지 않으면 거부한다.
 * - `DateTime64(9, 'UTC')` 는 `YYYY-MM-DD HH:MM:SS.nnnnnnnnn` 문자열이다. 타입의 하한(1900-01-01) 앞은 거부한다.
 * - `Float64` 는 가장 짧은 왕복 10진 표기의 **문자열**이다. 서버가 `toFloat64` 로 정밀 파싱한다([AnalysisInsert]).
 *
 * ClickHouse 는 범위 밖 값을 조용히 바꾼다(Decimal 소수 절삭, 1900 년 앞 시각 왜곡, FixedString 0 채움, 정수 wrap).
 * 그래서 거부는 여기서 한다 — 같은 입력은 다시 보내도 같은 결과이므로 [TelemetrySinkRejectedException](영구 오류)이다.
 * 선언과 어긋난 호출(순서·타입·null)은 인코더의 결함이라 [IllegalStateException] 이다.
 */
internal class AnalysisRowWriter(private val columns: List<ColumnSpec>) {

	private val out = StringBuilder("{")
	private var index = 0

	fun string(name: String, value: String?) = write(name, STRING, value == null) { writeString(value!!) }

	fun lowCardinality(name: String, value: WireValue) = write(name, LOW_CARDINALITY, false) { writeString(value.wire) }

	fun lowCardinality(name: String, value: String) = write(name, LOW_CARDINALITY, false) { writeString(value) }

	fun hex64(name: String, value: String?) = write(name, HEX64, value == null) {
		if (!Hex64.isValid(value!!)) reject("$name 은 64자리 소문자 hex 여야 한다")
		writeString(value)
	}

	fun int32(name: String, value: Int?) = write(name, "Int32", value == null) { out.append(value) }

	fun int64(name: String, value: Long?) = write(name, "Int64", value == null) { out.append(value) }

	fun uint8(name: String, value: Int?) = write(name, "UInt8", value == null) { unsigned(name, value!!, UINT8_MAX) }

	fun uint16(name: String, value: Int?) = write(name, "UInt16", value == null) { unsigned(name, value!!, UINT16_MAX) }

	fun uint32(name: String, value: UInt) = write(name, "UInt32", false) { out.append(value.toString()) }

	fun uint64(name: String, value: ULong?) = write(name, "UInt64", value == null) { out.append(value.toString()) }

	fun bool(name: String, value: Boolean?) = write(name, "Bool", value == null) { out.append(value) }

	fun money(name: String, value: BigDecimal?) = write(name, MONEY, value == null) {
		if (!Money.fits(value!!)) reject("$name 이 Decimal(38, 12) 에 반올림 없이 들어가지 않는다: ${value.toPlainString()}")
		out.append(value.stripTrailingZeros().toPlainString())
	}

	fun time(name: String, value: EpochNanos?) = write(name, TIME, value == null) {
		if (value!!.value < MIN_TIME_NANOS) reject("$name 이 DateTime64(9) 의 하한(1900-01-01) 앞이다: ${value.value}")
		writeString(formatTime(value))
	}

	fun float64(name: String, value: Double?) = write(name, "Float64", value == null) { writeFloat(name, value!!) }

	fun float64Array(name: String, values: List<Double>) = write(name, "Array(Float64)", false) {
		array(values) { writeFloat(name, it) }
	}

	fun uint64Array(name: String, values: List<ULong>) = write(name, "Array(UInt64)", false) {
		array(values) { out.append(it.toString()) }
	}

	fun stringArray(name: String, values: List<String>) = write(name, "Array(String)", false) {
		array(values) { writeString(it) }
	}

	fun lowCardinalityArray(name: String, values: List<WireValue>) = write(name, "Array(LowCardinality(String))", false) {
		array(values) { writeString(it.wire) }
	}

	fun stringMap(name: String, values: Map<String, String>) = write(name, "Map(LowCardinality(String), String)", false) {
		out.append('{')
		values.entries.forEachIndexed { i, (key, value) ->
			if (i > 0) out.append(',')
			writeString(key)
			out.append(':')
			writeString(value)
		}
		out.append('}')
	}

	/** 행을 닫는다. 선언된 컬럼을 전부 쓰지 않았으면 결함이다. */
	fun build(): String {
		check(index == columns.size) { "컬럼 ${columns.size} 개 중 $index 개만 썼다 — 다음은 ${columns[index]}" }
		return out.append('}').toString()
	}

	private inline fun write(name: String, baseType: String, isNull: Boolean, value: () -> Unit) {
		val column = columns.getOrNull(index) ?: error("선언된 컬럼 ${columns.size} 개를 넘어 $name 을 쓰려 한다")
		check(column.name == name) { "컬럼 순서가 선언과 다르다 — ${index + 1} 번째는 ${column.name} 인데 $name 을 썼다" }
		check(column.baseType == baseType) { "$name 의 타입은 ${column.type} 이다 — $baseType 로 쓸 수 없다" }
		check(!isNull || column.nullable) { "$name 은 Nullable 이 아니다 — null 을 쓸 수 없다" }
		if (index > 0) out.append(',')
		index++
		writeString(name)
		out.append(':')
		if (isNull) out.append("null") else value()
	}

	private inline fun <T> array(values: List<T>, element: (T) -> Unit) {
		out.append('[')
		values.forEachIndexed { i, value ->
			if (i > 0) out.append(',')
			element(value)
		}
		out.append(']')
	}

	private fun unsigned(name: String, value: Int, max: Int) {
		if (value !in 0..max) reject("$name 이 범위(0..$max) 밖이다: $value")
		out.append(value)
	}

	/** 비유한 값은 Float64 컬럼에 올리지 않는다 — 정규화가 null + `non_finite_value` 로 걸렀어야 한다. */
	private fun writeFloat(name: String, value: Double) {
		if (!value.isFinite()) reject("$name 에 유한하지 않은 값이 있다: $value")
		writeString(value.toString())
	}

	private fun writeString(value: String) {
		out.append('"')
		for (char in value) {
			when (char) {
				'"' -> out.append("\\\"")
				'\\' -> out.append("\\\\")
				'\n' -> out.append("\\n")
				'\r' -> out.append("\\r")
				'\t' -> out.append("\\t")
				'\b' -> out.append("\\b")
				'\u000C' -> out.append("\\f")
				else -> if (char < ' ') out.append("\\u").append("%04x".format(char.code)) else out.append(char)
			}
		}
		out.append('"')
	}

	private fun reject(message: String): Nothing = throw TelemetrySinkRejectedException(message)

	internal companion object {
		private const val STRING = "String"
		private const val LOW_CARDINALITY = "LowCardinality(String)"
		private const val HEX64 = "FixedString(64)"
		private const val MONEY = "Decimal(38, 12)"
		private const val TIME = "DateTime64(9, 'UTC')"
		private const val UINT8_MAX = 255
		private const val UINT16_MAX = 65_535

		/** `DateTime64(9)` 의 하한 1900-01-01T00:00:00Z 의 epoch 나노초. 상한은 `Long` 의 끝과 같다. */
		const val MIN_TIME_NANOS: Long = -2_208_988_800_000_000_000L

		private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSSSSS").withZone(ZoneOffset.UTC)

		fun formatTime(value: EpochNanos): String = TIME_FORMAT.format(value.toInstant())
	}
}
