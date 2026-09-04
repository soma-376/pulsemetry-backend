package com.team376.pulsemetry.persistence.telemetry

import java.math.BigDecimal

/**
 * 적재가 쓰는 JSON 표기. Python `json.dumps(..., ensure_ascii=False)` 의 이스케이프·실수 표기를 따른다.
 *
 * ## 규칙이 두 개다. 섞지 마라
 *
 * - [compact] — 키를 **정렬하지 않는다.** 행 하나(JSONEachRow 의 한 줄)가 이것이다.
 *   컬럼 순서는 [EnrichedEventRow.COLUMNS] 의 삽입 순서 그대로다.
 * - [sorted] — 키를 **정렬한다.** `enrichment_json` 컬럼의 값이 이것이다
 *   (`json.dumps(..., sort_keys=True, separators=(",", ":"))`).
 *
 * **바이트 동일성이 계약인 것은 [sorted] 뿐이다** — `enrichment_json` 은 저장되는 값 자체라
 * 구분자 하나가 달라도 현행과 다른 값이 된다. [compact] 는 JSONEachRow 의 입력이라 파서가 구분자
 * 공백을 무시하므로 표기가 저장 값에 영향을 주지 않는다. 어댑터에도 같은 성격의 인코더가 둘
 * 있지만 `internal` 이라 쓸 수 없다 — 그쪽 공개 API 는 `model/` 과 `NormalizedJson` 뿐으로
 * 두기로 한 결정(ADR 0013·0014) 때문에 표기 규칙만 여기 다시 세운다.
 */
internal object TelemetryJson {

	/** 키 순서를 보존한다. 행 한 줄이 이 표기다. */
	fun compact(value: Any?): String = StringBuilder().also { write(it, value, sortKeys = false) }.toString()

	/** 키를 코드 포인트 순으로 정렬한다. `enrichment_json` 이 이 표기다. */
	fun sorted(value: Any?): String = StringBuilder().also { write(it, value, sortKeys = true) }.toString()

	private fun write(out: StringBuilder, value: Any?, sortKeys: Boolean) {
		when (value) {
			null -> out.append("null")
			is Boolean -> out.append(if (value) "true" else "false")
			is String -> writeString(out, value)
			is Double -> out.append(formatDouble(value))
			is Float -> out.append(formatDouble(value.toDouble()))
			is Number -> out.append(value.toString())
			is Map<*, *> -> writeObject(out, value, sortKeys)
			is Iterable<*> -> writeArray(out, value, sortKeys)
			else -> throw IllegalArgumentException("JSON 으로 옮길 수 없는 값이다: ${value::class}")
		}
	}

	private fun writeObject(out: StringBuilder, value: Map<*, *>, sortKeys: Boolean) {
		out.append('{')
		val keys = value.keys.map { it as String }.let { if (sortKeys) it.sorted() else it }
		keys.forEachIndexed { index, key ->
			if (index > 0) out.append(',')
			writeString(out, key)
			out.append(':')
			write(out, value[key], sortKeys)
		}
		out.append('}')
	}

	private fun writeArray(out: StringBuilder, value: Iterable<*>, sortKeys: Boolean) {
		out.append('[')
		value.forEachIndexed { index, item ->
			if (index > 0) out.append(',')
			write(out, item, sortKeys)
		}
		out.append(']')
	}

	/**
	 * Python `json.encoder.py_encode_basestring` 과 같은 이스케이프.
	 *
	 * 따옴표·역슬래시와 제어문자만 이스케이프한다. `ensure_ascii=False` 라 그 위 코드 포인트는
	 * 전부 원문 그대로 나간다 — 한국어 프롬프트 길이나 파일명이 `\uXXXX` 로 부풀지 않는다.
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
	 * 현재 적재 값에는 실수가 없다 — 행의 `ts` 는 정수이고 `org` 의 산출물은 문자열 목록뿐이다.
	 * 그래도 구현해 두는 이유는, 실수를 내는 provider 가 새로 붙는 순간 표기가 조용히 갈리면
	 * `enrichment_json` 이 현행과 달라지기 때문이다. Java 기본 표기는 임계값도 모양도 다르다
	 * (`1.0E-7` vs `1e-07`).
	 */
	private fun formatDouble(value: Double): String {
		if (value.isNaN()) return "NaN"
		if (value == Double.POSITIVE_INFINITY) return "Infinity"
		if (value == Double.NEGATIVE_INFINITY) return "-Infinity"
		if (value == 0.0) return if (1.0 / value < 0) "-0.0" else "0.0"

		val decimal = BigDecimal(java.lang.Double.toString(value)).stripTrailingZeros()
		val digits = decimal.unscaledValue().abs().toString()
		val sign = if (decimal.signum() < 0) "-" else ""
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
