package com.team376.pulsemetry.persistence.telemetry

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 행 작성기의 두 방어선. 선언과 어긋난 호출은 인코더의 결함([IllegalStateException])이고, 컬럼 타입에 들어가지 않는 값은
 * 적재 거부([TelemetrySinkRejectedException] — 영구 오류)다. ClickHouse 는 그런 값을 조용히 바꾸므로(0 채움·wrap) 여기서 막는다.
 */
class AnalysisRowWriterTest {

	private fun writer(vararg columns: Pair<String, String>) = AnalysisRowWriter(columns.map { ColumnSpec(it.first, it.second) })

	@Test
	@DisplayName("선언 순서와 다른 컬럼·다른 타입·Nullable 아닌 컬럼의 null·빠진 컬럼은 인코더 결함이다")
	fun declarationMismatchesAreDefects() {
		assertThatThrownBy { writer("a" to "String", "b" to "String").string("b", "x") }
			.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("순서")
		assertThatThrownBy { writer("a" to "UInt64").int64("a", 1) }
			.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("UInt64")
		assertThatThrownBy { writer("a" to "String").string("a", null) }
			.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("Nullable")
		assertThatThrownBy { writer("a" to "String", "b" to "String").apply { string("a", "x") }.build() }
			.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("b String")
	}

	@Test
	@DisplayName("FixedString(64) 는 64자리 소문자 hex 만 — 짧으면 ClickHouse 가 0 으로 채운다")
	fun hex64IsValidated() {
		assertThatThrownBy { writer("id" to "FixedString(64)").hex64("id", "abc") }
			.isInstanceOf(TelemetrySinkRejectedException::class.java)
		assertThatThrownBy { writer("id" to "FixedString(64)").hex64("id", "A".repeat(64)) }
			.isInstanceOf(TelemetrySinkRejectedException::class.java)
	}

	@Test
	@DisplayName("UInt8·UInt16 범위 밖은 거부한다 — ClickHouse 는 wrap 한다")
	fun unsignedRangesAreValidated() {
		assertThat(writer("n" to "Nullable(UInt8)").apply { uint8("n", 255) }.build()).isEqualTo("{\"n\":255}")
		assertThatThrownBy { writer("n" to "Nullable(UInt8)").uint8("n", 256) }.isInstanceOf(TelemetrySinkRejectedException::class.java)
		assertThatThrownBy { writer("n" to "UInt16").uint16("n", -1) }.isInstanceOf(TelemetrySinkRejectedException::class.java)
		assertThatThrownBy { writer("n" to "UInt16").uint16("n", 65_536) }.isInstanceOf(TelemetrySinkRejectedException::class.java)
	}

	@Test
	@DisplayName("Float64 는 가장 짧은 왕복 표기의 문자열이고 비유한 값은 거부한다")
	fun floatsAreShortestStrings() {
		val row = writer("f" to "Nullable(Float64)", "a" to "Array(Float64)")
			.apply {
				float64("f", 0.1 + 0.2)
				float64Array("a", listOf(1.0E-7, 12.0, -0.0))
			}.build()

		assertThat(row).isEqualTo("{\"f\":\"0.30000000000000004\",\"a\":[\"1.0E-7\",\"12.0\",\"-0.0\"]}")
		assertThatThrownBy { writer("f" to "Nullable(Float64)").float64("f", Double.NaN) }
			.isInstanceOf(TelemetrySinkRejectedException::class.java)
		assertThatThrownBy { writer("a" to "Array(Float64)").float64Array("a", listOf(Double.POSITIVE_INFINITY)) }
			.isInstanceOf(TelemetrySinkRejectedException::class.java)
	}

	@Test
	@DisplayName("문자열은 따옴표·역슬래시·제어문자만 이스케이프한다")
	fun stringsAreEscaped() {
		assertThat(writer("s" to "String").apply { string("s", "팀 \"A\"\\\n\u0001") }.build())
			.isEqualTo("{\"s\":\"팀 \\\"A\\\"\\\\\\n\\u0001\"}")
	}
}
