package com.team376.pulsemetry.telemetry.adapter.observation

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** `metadata_json` 의 typed 원형이 타입·중복·정밀도를 잃지 않고 왕복하는지 본다 (ADR 0020 §1). */
class TypedMetadataTest {

	private val everyType = TypedMetadata(
		resource = listOf(TypedAttribute("service.name", TypedValue.Str("codex-app-server"))),
		scope = listOf(TypedAttribute("scope.flag", TypedValue.Bool(true))),
		record = listOf(
			// 같은 키가 서로 다른 타입으로 두 번 — 합치지 않는다.
			TypedAttribute("thread.id", TypedValue.Int(9_007_199_254_740_993L)),
			TypedAttribute("thread.id", TypedValue.Str("thread-example")),
			TypedAttribute("ratio", TypedValue.Double(0.25)),
			TypedAttribute("nan", TypedValue.Double(Double.NaN)),
			TypedAttribute("inf", TypedValue.Double(Double.NEGATIVE_INFINITY)),
			TypedAttribute("raw", TypedValue.bytes(byteArrayOf(0, 1, 2, -1))),
			TypedAttribute(
				"nested",
				TypedValue.KvList(
					listOf(
						TypedAttribute("list", TypedValue.Array(listOf(TypedValue.Int(Long.MAX_VALUE), TypedValue.Str("x")))),
						TypedAttribute("empty", TypedValue.Empty),
					),
				),
			),
		),
		exemplars = listOf(listOf(TypedAttribute("trace", TypedValue.Str("t1")))),
	)

	@Test
	@DisplayName("모든 AnyValue 타입·중복 키·경계가 왕복한다")
	fun roundTripsEveryType() {
		assertThat(TypedMetadata.fromJson(everyType.toJson())).isEqualTo(everyType)
	}

	@Test
	@DisplayName("2^53 을 넘는 정수는 문자열 intValue 로 써서 정밀도를 잃지 않는다")
	fun keepsIntegersBeyondDoublePrecision() {
		val json = everyType.toJson()

		assertThat(json).contains("\"intValue\":\"9007199254740993\"")
		val reread = TypedMetadata.fromJson(json).record.first()
		assertThat(reread.value).isEqualTo(TypedValue.Int(9_007_199_254_740_993L))
	}

	@Test
	@DisplayName("같은 값이면 언제나 같은 바이트다 — 원래 순서를 그대로 쓴다")
	fun serializationIsDeterministicAndOrderPreserving() {
		val metadata = TypedMetadata(record = listOf(TypedAttribute("b", TypedValue.Str("2")), TypedAttribute("a", TypedValue.Str("1"))))

		assertThat(metadata.toJson()).isEqualTo(metadata.copy().toJson())
		assertThat(metadata.toJson()).isEqualTo(
			"""{"resource":[],"scope":[],"record":[{"key":"b","value":{"stringValue":"2"}},{"key":"a","value":{"stringValue":"1"}}]}""",
		)
	}

	@Test
	@DisplayName("비유한 double 은 OTLP/JSON 처럼 문자열이다")
	fun nonFiniteDoublesAreStrings() {
		val json = everyType.toJson()

		assertThat(json).contains("\"doubleValue\":\"NaN\"").contains("\"doubleValue\":\"-Infinity\"")
	}

	@Test
	@DisplayName("OTLP/JSON 이 숫자로 쓴 intValue 도 받는다")
	fun acceptsNumericIntValue() {
		val parsed = TypedMetadata.fromJson("""{"record":[{"key":"n","value":{"intValue":42}}]}""")

		assertThat(parsed.record.single().value).isEqualTo(TypedValue.Int(42))
		assertThat(parsed.resource).isEmpty()
	}

	@Test
	@DisplayName("최상위 eventName 은 있을 때만 쓰고 왕복한다")
	fun eventNameIsOptional() {
		val withName = TypedMetadata(record = listOf(TypedAttribute("k", TypedValue.Str("v"))), eventName = "event a/b.rs:1")

		assertThat(TypedMetadata().toJson()).doesNotContain("eventName")
		assertThat(withName.toJson()).isEqualTo("""{"resource":[],"scope":[],"record":[{"key":"k","value":{"stringValue":"v"}}],"eventName":"event a/b.rs:1"}""")
		assertThat(TypedMetadata.fromJson(withName.toJson())).isEqualTo(withName)
		assertThatThrownBy { TypedMetadata.fromJson("""{"eventName":1}""") }.isInstanceOf(IllegalArgumentException::class.java)
	}

	@Test
	@DisplayName("모르는 경계나 필드는 거부한다")
	fun rejectsUnknownShapes() {
		assertThatThrownBy { TypedMetadata.fromJson("""{"records":[]}""") }.isInstanceOf(IllegalArgumentException::class.java)
		assertThatThrownBy { TypedMetadata.fromJson("""{"record":[{"key":"n","value":{"floatValue":1}}]}""") }
			.isInstanceOf(IllegalArgumentException::class.java)
	}
}
