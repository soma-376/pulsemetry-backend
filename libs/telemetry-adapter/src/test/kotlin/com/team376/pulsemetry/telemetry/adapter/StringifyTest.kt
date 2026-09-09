package com.team376.pulsemetry.telemetry.adapter

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Python `str()` 표기 — `Lifecycle.attrs`·`MetricPoint.attrs` 의 값이 이 표기로 굳는다.
 *
 * [CanonicalJson] 의 소문자 `true`/`null` 은 JSON **문법**이고 이쪽은 **값 자체**다.
 * 둘을 섞으면 속성을 가진 이벤트가 전부 어긋난다.
 */
internal class StringifyTest {

	@Test
	@DisplayName("Boolean 은 대문자로 시작한다 — Kotlin 기본 표기가 아니다")
	fun booleansAreCapitalized() {
		assertThat(Stringify.of(true)).isEqualTo("True")
		assertThat(Stringify.of(false)).isEqualTo("False")
	}

	@Test
	@DisplayName("null 은 None 이다")
	fun nullIsNone() {
		assertThat(Stringify.of(null)).isEqualTo("None")
	}

	@Test
	@DisplayName("정수와 문자열은 그대로, 실수는 repr 표기다")
	fun numbersAndStringsFollowPython() {
		assertThat(Stringify.of(1L)).isEqualTo("1")
		assertThat(Stringify.of(7)).isEqualTo("7")
		assertThat(Stringify.of("x")).isEqualTo("x")
		assertThat(Stringify.of(3.5)).isEqualTo("3.5")
		// 정수값 실수도 소수점을 유지한다.
		assertThat(Stringify.of(12.0)).isEqualTo("12.0")
	}

	@Test
	@DisplayName("속성 맵의 값을 전부 문자열로 눕힌다")
	fun stringifiesAttributeMaps() {
		assertThat(Stringify.attrs(mapOf("enabled" to true, "ratio" to 0.25, "count" to 7L)))
			.isEqualTo(mapOf("enabled" to "True", "ratio" to "0.25", "count" to "7"))
	}
}
