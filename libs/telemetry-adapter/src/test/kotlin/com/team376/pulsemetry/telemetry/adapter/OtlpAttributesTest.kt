package com.team376.pulsemetry.telemetry.adapter

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 속성 추출의 규칙 — **값이 없으면 0 이 아니라 null 이다.** */
internal class OtlpAttributesTest {

	@Test
	@DisplayName("AnyValue 의 네 스칼라만 읽고 나머지는 null 이다")
	fun readsOnlyTheFourScalars() {
		assertThat(OtlpAttributes.value(mapOf("stringValue" to "x"))).isEqualTo("x")
		assertThat(OtlpAttributes.value(mapOf("intValue" to "101"))).isEqualTo(101L)
		assertThat(OtlpAttributes.value(mapOf("doubleValue" to 1.5))).isEqualTo(1.5)
		assertThat(OtlpAttributes.value(mapOf("boolValue" to true))).isEqualTo(true)
		// arrayValue·kvlistValue·bytesValue 는 이식 원본이 보지 않는다.
		assertThat(OtlpAttributes.value(mapOf("arrayValue" to emptyList<Any>()))).isNull()
		assertThat(OtlpAttributes.value(mapOf("bytesValue" to "AA=="))).isNull()
	}

	@Test
	@DisplayName("Boolean 은 정수로 읽지 않는다")
	fun booleansAreNotIntegers() {
		// Python 의 bool 이 int 의 하위 타입이라 원본이 명시적으로 걸러 냈다. 그 판정을 유지한다.
		assertThat(OtlpAttributes.optInt(mapOf("k" to true), "k")).isNull()
		assertThat(OtlpAttributes.optDouble(mapOf("k" to true), "k")).isNull()
	}

	@Test
	@DisplayName("정수는 부호 없는 숫자 문자열만 받는다")
	fun integerStringsMustBeAllDigits() {
		assertThat(OtlpAttributes.optInt(mapOf("k" to "42"), "k")).isEqualTo(42)
		// 원본이 isdigit() 으로 거르므로 음수 문자열은 버려진다.
		assertThat(OtlpAttributes.optInt(mapOf("k" to "-5"), "k")).isNull()
		// 실수는 절단된다.
		assertThat(OtlpAttributes.optInt(mapOf("k" to 3.9), "k")).isEqualTo(3)
	}

	@Test
	@DisplayName("빈 문자열은 없는 것으로 본다")
	fun emptyStringCountsAsAbsent() {
		assertThat(OtlpAttributes.optString(mapOf("k" to ""), "k")).isNull()
		assertThat(OtlpAttributes.optString(mapOf("k" to "", "j" to "v"), "k", "j")).isEqualTo("v")
	}

	@Test
	@DisplayName("소스를 먼저 돌고 그 안에서 키를 돈다 — 순서가 뒤바뀌면 우선순위가 뒤집힌다")
	fun sourceOrderBeatsKeyOrder() {
		val primary = mapOf("second" to "primary-second")
		val secondary = mapOf("first" to "secondary-first")

		// 앞 소스에 뒤쪽 키가 있으면 그것이 이긴다 — 뒤 소스의 앞쪽 키가 아니다.
		assertThat(OtlpAttributes.optString(primary, secondary, "first", "second"))
			.isEqualTo("primary-second")
	}

	@Test
	@DisplayName("JSON 문자열 인자를 병합하고 `{` 로 시작하지 않으면 무시한다")
	fun mergesJsonAttributes() {
		val attrs = mapOf(
			"arguments" to """{"command":"ls","path":"a\\b"}""",
			"input" to "not json",
		)
		val merged = OtlpAttributes.mergeJsonAttrs(attrs, "arguments", "input")

		assertThat(merged["command"]).isEqualTo("ls")
		assertThat(OtlpAttributes.extractFiles(merged, arrayOf("path"))).isEqualTo(listOf("a/b"))
		assertThat(OtlpAttributes.extractCommand(merged, arrayOf("command"))).isEqualTo("ls")
	}
}
