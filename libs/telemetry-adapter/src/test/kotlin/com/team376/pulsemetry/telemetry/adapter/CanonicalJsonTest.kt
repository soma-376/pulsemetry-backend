package com.team376.pulsemetry.telemetry.adapter

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Python `json.dumps(..., sort_keys=True, ensure_ascii=False)` 와 바이트가 같은지.
 *
 * 기대값은 손으로 적은 것이 아니라 **Python 3.13 에서 실제로 찍어 본 값이다.**
 * `_ingest.source_record_id` 가 이 표기의 SHA-1 이라, 한 글자만 달라도 golden 전체가 어긋난다.
 * **기대값을 고치지 마라** — 어긋나면 인코더를 고쳐라.
 */
internal class CanonicalJsonTest {

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource(
		value = [
			"0.0|0.0",
			"1.0|1.0",
			"12.0|12.0",
			"3.5|3.5",
			"0.0421|0.0421",
			"0.0031|0.0031",
			"1234.5|1234.5",
			"800.25|800.25",
			"0.25|0.25",
			"-2.5|-2.5",
			"123456789.123|123456789.123",
			// 고정 표기의 위쪽 경계 — decpt 16 까지는 소수 표기다.
			"1.0E15|1000000000000000.0",
			"1.0E16|1e+16",
			"1.0E17|1e+17",
			// 아래쪽 경계 — decpt -3 까지는 소수 표기다.
			"1.0E-4|0.0001",
			"1.0E-5|1e-05",
			"1.5E-7|1.5e-07",
			"1.0E21|1e+21",
		],
		delimiter = '|',
	)
	@DisplayName("실수를 Python repr 표기로 적는다")
	fun formatsDoublesLikePython(value: Double, expected: String) {
		assertThat(CanonicalJson.formatDouble(value)).isEqualTo(expected)
	}

	@Test
	@DisplayName("음의 0 과 특수값도 Python 을 따른다")
	fun formatsSpecialDoublesLikePython() {
		assertThat(CanonicalJson.formatDouble(-0.0)).isEqualTo("-0.0")
		// json.dumps 는 표준을 벗어나 이 세 값을 그대로 쓴다.
		assertThat(CanonicalJson.formatDouble(Double.NaN)).isEqualTo("NaN")
		assertThat(CanonicalJson.formatDouble(Double.POSITIVE_INFINITY)).isEqualTo("Infinity")
		assertThat(CanonicalJson.formatDouble(Double.NEGATIVE_INFINITY)).isEqualTo("-Infinity")
	}

	@Test
	@DisplayName("키를 정렬하고 구분자에 공백을 넣는다")
	fun sortsKeysAndKeepsTheSpaces() {
		// compact 표기가 아니다 — `", "` 와 `": "` 다. json.dumps 의 기본값이다.
		val encoded = CanonicalJson.encode(
			linkedMapOf("b" to 1L, "a" to listOf(1L, 2L), "c" to null, "d" to true),
		)
		assertThat(encoded).isEqualTo("""{"a": [1, 2], "b": 1, "c": null, "d": true}""")
	}

	@Test
	@DisplayName("비 ASCII 는 그대로 쓰고 따옴표·역슬래시·개행만 이스케이프한다")
	fun keepsNonAsciiAndEscapesOnlyWhatPythonEscapes() {
		val encoded = CanonicalJson.encode(
			linkedMapOf("ko" to "한글", "esc" to "a\"b\\c\nd"),
		)
		assertThat(encoded).isEqualTo("""{"esc": "a\"b\\c\nd", "ko": "한글"}""")
	}

	@Test
	@DisplayName("중첩 객체의 키도 정렬한다")
	fun sortsNestedKeysToo() {
		val encoded = CanonicalJson.encode(
			linkedMapOf("z" to linkedMapOf("y" to 1L, "x" to 2L)),
		)
		assertThat(encoded).isEqualTo("""{"z": {"x": 2, "y": 1}}""")
	}

	@Test
	@DisplayName("배열 순서는 건드리지 않는다")
	fun preservesArrayOrder() {
		assertThat(CanonicalJson.encode(listOf("c", "a", "b")))
			.isEqualTo("""["c", "a", "b"]""")
	}
}
