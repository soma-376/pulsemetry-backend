package com.team376.pulsemetry.telemetry.adapter

import java.math.BigDecimal

/**
 * Python `json.dumps(obj, sort_keys=True, ensure_ascii=False)` 와 **바이트가 같은** 인코더.
 *
 * `_ingest.source_record_id` 는 원본 레코드 JSON 의 SHA-1 이고, 그 해시의 재료가 바로 이
 * 표기다([SourceRecordId]). 한 글자만 달라도 golden fixture 의 `raw-` 값이 전부 어긋난다.
 *
 * ## 반드시 지켜야 하는 것 — 전부 Python 기본값이라 눈에 잘 안 띈다
 *
 * - **구분자에 공백이 있다.** 항목 사이는 `", "`, 키와 값 사이는 `": "` 다.
 *   `json.dumps` 의 기본값이며 compact(`","`/`":"`)가 **아니다.**
 * - **키를 정렬한다.** 중첩 객체도 전부. 배열 순서는 건드리지 않는다.
 * - **`ensure_ascii=False`** — 비 ASCII 문자를 유니코드 이스케이프로 바꾸지 않고 그대로 쓴다.
 * - 실수는 Python `repr` 표기다 ([formatDouble]).
 * - `null`·`true`·`false` 는 소문자다. **여기서만 그렇다** — 해시가 아니라 문자열 승격
 *   (`Lifecycle.attrs`·`MetricPoint.attrs`)에서는 Python `str()` 이라 `True` 가 된다.
 */
internal object CanonicalJson {

	/** 트리를 Python 과 같은 표기의 JSON 문자열로 만든다. */
	fun encode(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

	private fun write(out: StringBuilder, value: Any?) {
		when (value) {
			null -> out.append("null")
			is Boolean -> out.append(if (value) "true" else "false")
			is String -> writeString(out, value)
			is Double -> out.append(formatDouble(value))
			is Float -> out.append(formatDouble(value.toDouble()))
			is Number -> out.append(value.toString())
			is Map<*, *> -> writeObject(out, value)
			is Iterable<*> -> writeArray(out, value)
			else -> throw IllegalArgumentException("JSON 으로 옮길 수 없는 값이다: ${value::class}")
		}
	}

	private fun writeObject(out: StringBuilder, value: Map<*, *>) {
		out.append('{')
		// sort_keys=True — 코드 포인트 순. Kotlin 의 String 비교가 그것과 같다.
		val keys = value.keys.map { it as String }.sorted()
		keys.forEachIndexed { index, key ->
			if (index > 0) out.append(", ")
			writeString(out, key)
			out.append(": ")
			write(out, value[key])
		}
		out.append('}')
	}

	private fun writeArray(out: StringBuilder, value: Iterable<*>) {
		out.append('[')
		value.forEachIndexed { index, item ->
			if (index > 0) out.append(", ")
			write(out, item)
		}
		out.append(']')
	}

	/**
	 * Python `json.encoder.py_encode_basestring` 과 같은 이스케이프.
	 *
	 * 따옴표·역슬래시와 제어문자(U+0000..U+001F)만 이스케이프한다. `/` 는 건드리지 않고,
	 * `ensure_ascii=False` 라 그 위 코드 포인트는 전부 원문 그대로 나간다.
	 */
	private fun writeString(out: StringBuilder, value: String) {
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
				else ->
					if (char < ' ') {
						out.append("\\u").append("%04x".format(char.code))
					} else {
						out.append(char)
					}
			}
		}
		out.append('"')
	}

	/**
	 * Python `repr(float)` 표기.
	 *
	 * 자릿수는 왕복 가능한 최단 표기다 — JDK 19 부터 `Double.toString` 이 그것을 보장한다
	 * (JDK-4511638). 남는 차이는 **어디서 지수 표기로 넘어가는가**와 그 모양뿐이다.
	 *
	 * Python 은 소수점 위치 `decpt` 가 `16 < decpt` 이거나 `decpt < -3` 일 때 지수 표기로
	 * 가고, 지수를 최소 두 자리로 적는다 — `1e-05` 이지 `1e-5` 가 아니다. 정수값에는 `.0` 을
	 * 붙인다. Java 는 임계값도 모양도 다르므로(`1.0E-7`) 여기서 다시 만든다.
	 */
	internal fun formatDouble(value: Double): String {
		// json.dumps 는 표준을 벗어나 이 세 값을 그대로 쓴다.
		if (value.isNaN()) return "NaN"
		if (value == Double.POSITIVE_INFINITY) return "Infinity"
		if (value == Double.NEGATIVE_INFINITY) return "-Infinity"
		if (value == 0.0) return if (1.0 / value < 0) "-0.0" else "0.0"

		val decimal = BigDecimal(java.lang.Double.toString(value)).stripTrailingZeros()
		val digits = decimal.unscaledValue().abs().toString()
		val sign = if (decimal.signum() < 0) "-" else ""
		// 소수점 위치. 0.5 -> 0, 12.0 -> 2, 1e16 -> 17.
		val decpt = digits.length - decimal.scale()

		if (decpt in -3..16) {
			val plain = decimal.toPlainString()
			return if (plain.contains('.')) plain else "$plain.0"
		}

		val mantissa = if (digits.length == 1) digits else "${digits[0]}.${digits.substring(1)}"
		val exponent = decpt - 1
		val exponentSign = if (exponent < 0) "-" else "+"
		return sign + mantissa + "e" + exponentSign + "%02d".format(kotlin.math.abs(exponent))
	}
}
