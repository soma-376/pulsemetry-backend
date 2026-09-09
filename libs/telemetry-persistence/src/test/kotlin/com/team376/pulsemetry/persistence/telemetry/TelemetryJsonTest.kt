package com.team376.pulsemetry.persistence.telemetry

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 표기 규칙 둘을 갈라 고정한다.
 *
 * `enrichment_json` 은 저장되는 문자열이라 표기가 바뀌면 값이 바뀐다. 특히 **비 ASCII 를
 * 이스케이프하지 않는 것**(`ensure_ascii=False`)이 눈에 잘 안 띈다 — 켜지면 한국어가 든
 * 모든 주석의 바이트가 달라진다.
 */
class TelemetryJsonTest {

	@Test
	@DisplayName("compact 는 키 순서를 보존하고 sorted 는 정렬한다")
	fun theTwoNotationsDifferOnlyInKeyOrder() {
		val value = linkedMapOf("b" to 1, "a" to 2)

		assertThat(TelemetryJson.compact(value)).isEqualTo("{\"b\":1,\"a\":2}")
		assertThat(TelemetryJson.sorted(value)).isEqualTo("{\"a\":2,\"b\":1}")
	}

	@Test
	@DisplayName("중첩 객체까지 정렬한다")
	fun sortingReachesNestedObjects() {
		val value = mapOf("z" to linkedMapOf("y" to 1, "x" to 2))

		assertThat(TelemetryJson.sorted(value)).isEqualTo("{\"z\":{\"x\":2,\"y\":1}}")
	}

	@Test
	@DisplayName("배열 순서는 건드리지 않는다")
	fun arrayOrderIsNeverTouched() {
		assertThat(TelemetryJson.sorted(mapOf("team_ids" to listOf("b", "a"))))
			.isEqualTo("{\"team_ids\":[\"b\",\"a\"]}")
	}

	@Test
	@DisplayName("구분자에 공백이 없다")
	fun separatorsCarryNoWhitespace() {
		assertThat(TelemetryJson.compact(mapOf("a" to 1, "b" to listOf(1, 2))))
			.isEqualTo("{\"a\":1,\"b\":[1,2]}")
	}

	@Test
	@DisplayName("비 ASCII 는 그대로 나간다 — ensure_ascii=False")
	fun nonAsciiIsNotEscaped() {
		assertThat(TelemetryJson.compact(mapOf("k" to "한국어"))).isEqualTo("{\"k\":\"한국어\"}")
	}

	@Test
	@DisplayName("따옴표·역슬래시·제어문자만 이스케이프한다 — 슬래시는 건드리지 않는다")
	fun onlyQuotesBackslashesAndControlCharactersAreEscaped() {
		val encoded = TelemetryJson.compact(mapOf("k" to "a\"b\\c\nd\te/f"))

		assertThat(encoded).isEqualTo("{\"k\":\"a\\\"b\\\\c\\nd\\te/f\"}")
	}

	@Test
	@DisplayName("실수는 Python repr 표기다 — Java 기본값이 아니다")
	fun floatsUsePythonRepr() {
		assertThat(TelemetryJson.compact(1.0)).isEqualTo("1.0")
		assertThat(TelemetryJson.compact(0.5)).isEqualTo("0.5")
		assertThat(TelemetryJson.compact(1e-5)).isEqualTo("1e-05")
		assertThat(TelemetryJson.compact(1e17)).isEqualTo("1e+17")
	}

	@Test
	@DisplayName("null 과 boolean 은 소문자 JSON 리터럴이다 — 값 승격의 Python str() 표기와 다르다")
	fun literalsAreLowercase() {
		assertThat(TelemetryJson.compact(mapOf("a" to null, "b" to true)))
			.isEqualTo("{\"a\":null,\"b\":true}")
	}
}
