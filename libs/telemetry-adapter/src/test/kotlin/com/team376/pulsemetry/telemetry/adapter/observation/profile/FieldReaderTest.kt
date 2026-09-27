package com.team376.pulsemetry.telemetry.adapter.observation.profile

import com.team376.pulsemetry.telemetry.adapter.observation.QualityFlag
import com.team376.pulsemetry.telemetry.adapter.observation.TypedAttribute
import com.team376.pulsemetry.telemetry.adapter.observation.TypedValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class FieldReaderTest {

	private fun reader(vararg attributes: Pair<String, TypedValue>) = FieldReader(attributes.map { TypedAttribute(it.first, it.second) })

	@Test
	@DisplayName("누락은 null 이고 플래그가 없다 — 0 이나 false 로 바꾸지 않는다")
	fun missingIsNull() {
		val r = reader()

		assertThat(r.count("n", NumberWire.INT)).isNull()
		assertThat(r.bool("b", BoolWire.BOOL)).isNull()
		assertThat(r.flags).isEmpty()
	}

	@Test
	@DisplayName("허용한 wire 만 읽는다 — 정수는 intValue, 10진 문자열은 엄격한 표기만")
	fun strictNumbers() {
		val r = reader(
			"int" to TypedValue.Int(0), "dec" to TypedValue.Str("9007199254740993"), "wrongWire" to TypedValue.Str("5"),
		)

		assertThat(r.count("int", NumberWire.INT)).isEqualTo(0L)
		assertThat(r.count("dec", NumberWire.DECIMAL_STRING)).isEqualTo(9_007_199_254_740_993L)
		assertThat(r.flags).isEmpty()
		assertThat(r.count("wrongWire", NumberWire.INT)).isNull()
		assertThat(r.flags).containsExactly(QualityFlag.INVALID_MEASUREMENT)
	}

	@Test
	@DisplayName("부호·앞자리 0·소수·지수·공백·overflow·음수 정수는 invalid")
	fun rejectsMalformedNumbers() {
		for (text in listOf("-1", "+1", "01", "1.0", "1e3", " 1", "", "99999999999999999999")) {
			val r = reader("n" to TypedValue.Str(text))
			assertThat(r.count("n", NumberWire.DECIMAL_STRING)).describedAs(text).isNull()
			assertThat(r.flags).describedAs(text).containsExactly(QualityFlag.INVALID_MEASUREMENT)
		}
		val negative = reader("n" to TypedValue.Int(-1))
		assertThat(negative.count("n", NumberWire.INT)).isNull()
		assertThat(negative.flags).containsExactly(QualityFlag.INVALID_MEASUREMENT)
	}

	@Test
	@DisplayName("같은 키가 다른 값이면 ambiguous_attribute, 같은 값의 중복은 한 값")
	fun duplicates() {
		val conflict = reader("n" to TypedValue.Int(1), "n" to TypedValue.Int(2))
		val same = reader("n" to TypedValue.Int(3), "n" to TypedValue.Int(3))

		assertThat(conflict.count("n", NumberWire.INT)).isNull()
		assertThat(conflict.flags).containsExactly(QualityFlag.AMBIGUOUS_ATTRIBUTE)
		assertThat(same.count("n", NumberWire.INT)).isEqualTo(3L)
		assertThat(same.flags).isEmpty()
	}

	@Test
	@DisplayName("불리언은 boolValue 또는 정확한 true/false 문자열만")
	fun booleans() {
		val r = reader("b" to TypedValue.Bool(false), "s" to TypedValue.Str("true"), "bad" to TypedValue.Str("True"))

		assertThat(r.bool("b", BoolWire.BOOL)).isFalse()
		assertThat(r.bool("s", BoolWire.STRING)).isTrue()
		assertThat(r.bool("s", BoolWire.BOOL)).isNull()
		assertThat(r.bool("bad", BoolWire.STRING)).isNull()
		assertThat(r.flags).containsExactly(QualityFlag.INVALID_MEASUREMENT)
	}

	@Test
	@DisplayName("double 금액은 가장 짧은 10진 표기 그대로 — 정수부가 Decimal(38, 12) 범위(26자리)를 넘으면 invalid")
	fun doubleAmounts() {
		val r = reader(
			"ok" to TypedValue.Double(0.0031),
			"artifact" to TypedValue.Double(0.1 + 0.2),
			"largest" to TypedValue.Double(9.0E25),
			"tooLarge" to TypedValue.Double(1.0E26),
		)

		assertThat(r.nonNegativeDouble("ok")).isEqualByComparingTo("0.0031")
		// 소수 자릿수는 그대로 둔다 — 컬럼에 맞추는 규칙은 프로파일의 몫이다.
		assertThat(r.nonNegativeDouble("artifact")).isEqualByComparingTo("0.30000000000000004")
		assertThat(r.nonNegativeDouble("largest")).isEqualByComparingTo("90000000000000000000000000")
		assertThat(r.flags).isEmpty()
		assertThat(r.nonNegativeDouble("tooLarge")).isNull()
		assertThat(r.flags).containsExactly(QualityFlag.INVALID_MEASUREMENT)
	}

	@Test
	@DisplayName("밀리초를 나노초로 — 넘치면 invalid")
	fun millis() {
		val r = reader("ok" to TypedValue.Str("12"), "big" to TypedValue.Str(Long.MAX_VALUE.toString()))

		assertThat(r.millisAsNanos("ok", NumberWire.DECIMAL_STRING)).isEqualTo(12_000_000L)
		assertThat(r.millisAsNanos("big", NumberWire.DECIMAL_STRING)).isNull()
		assertThat(r.flags).containsExactly(QualityFlag.INVALID_MEASUREMENT)
	}
}
