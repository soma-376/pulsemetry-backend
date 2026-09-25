package com.team376.pulsemetry.telemetry.adapter.observation

import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.core.json.JsonFactory
import java.io.StringWriter
import java.util.Base64

/**
 * OTLP `AnyValue` 의 타입을 잃지 않는 값. `metadata_json` 의 원소다(ADR 0020 §1).
 *
 * 정수는 `Long` 으로 정확히 담는다 — 2^53 을 넘어도 Double 을 거치지 않는다. bytes 는 표준 base64 문자열로
 * 담아 값 동등성이 성립하게 한다.
 */
public sealed interface TypedValue {
	public data class Str(val value: String) : TypedValue
	public data class Bool(val value: Boolean) : TypedValue
	public data class Int(val value: Long) : TypedValue
	public data class Double(val value: kotlin.Double) : TypedValue
	public data class Bytes(val base64: String) : TypedValue
	public data class Array(val values: List<TypedValue>) : TypedValue
	public data class KvList(val values: List<TypedAttribute>) : TypedValue

	/** 값이 설정되지 않은 `AnyValue`. */
	public data object Empty : TypedValue

	public companion object {
		public fun bytes(bytes: ByteArray): Bytes = Bytes(Base64.getEncoder().encodeToString(bytes))
	}
}

/** OTLP `KeyValue` 하나. 같은 키가 여러 번 와도 합치지 않는다 — 배열의 multiplicity 가 원형이다. */
public data class TypedAttribute(val key: String, val value: TypedValue)

/**
 * allowlist 를 통과한 속성의 typed 원형. resource·scope·record 경계를 유지하고, 각 경계 안의 원래 순서와
 * 중복 키를 그대로 둔다. metric point 의 exemplar 는 point 순서대로 [exemplars] 에 따로 둔다.
 *
 * [eventName] 은 속성이 아니라 로그 레코드의 최상위 `eventName` 필드다. 그 값이 무엇인지 검증한 프로파일만
 * 싣는다(예: producer 의 소스 위치 — `operation` 판별 근거, ADR 0020 §4). generic 경로는 싣지 않는다.
 *
 * [toJson] 이 `metadata_json` 의 값이다. 형식은 OTLP/JSON 의 `KeyValue`·`AnyValue` 표기를 따른다 —
 * `intValue` 는 문자열, `bytesValue` 는 base64, 비유한 `doubleValue` 는 `"NaN"`·`"Infinity"`·`"-Infinity"` 문자열.
 * 같은 값이면 언제나 같은 바이트다. 순서를 정렬한 canonical 형태(해시 재료)는 이것과 따로다(ADR 0020 §2).
 */
public data class TypedMetadata(
	val resource: List<TypedAttribute> = emptyList(),
	val scope: List<TypedAttribute> = emptyList(),
	val record: List<TypedAttribute> = emptyList(),
	val exemplars: List<List<TypedAttribute>> = emptyList(),
	val eventName: String? = null,
) {

	/** `resource`·`scope`·`record` 는 언제나 쓰고, `eventName` 은 있을 때만, `exemplars` 는 비어 있지 않을 때만 쓴다. */
	public fun toJson(): String {
		val out = StringWriter()
		FACTORY.createGenerator(out).use { generator ->
			generator.writeStartObject()
			writeSection(generator, RESOURCE, resource)
			writeSection(generator, SCOPE, scope)
			writeSection(generator, RECORD, record)
			eventName?.let { generator.writeStringProperty(EVENT_NAME, it) }
			if (exemplars.isNotEmpty()) {
				generator.writeName(EXEMPLARS)
				generator.writeStartArray()
				exemplars.forEach { writeAttributes(generator, it) }
				generator.writeEndArray()
			}
			generator.writeEndObject()
		}
		return out.toString()
	}

	public companion object {
		private val FACTORY = JsonFactory()
		private const val RESOURCE = "resource"
		private const val SCOPE = "scope"
		private const val RECORD = "record"
		private const val EXEMPLARS = "exemplars"
		private const val EVENT_NAME = "eventName"

		public val EMPTY: TypedMetadata = TypedMetadata()

		/** [toJson] 의 역. 형식이 아니면 [IllegalArgumentException] 이다. */
		public fun fromJson(json: String): TypedMetadata =
			FACTORY.createParser(json).use { parser ->
				expect(parser.nextToken(), JsonToken.START_OBJECT)
				var metadata = TypedMetadata()
				while (parser.nextToken() != JsonToken.END_OBJECT) {
					val name = parser.currentName()
					parser.nextToken()
					metadata = when (name) {
						RESOURCE -> metadata.copy(resource = readAttributes(parser))
						SCOPE -> metadata.copy(scope = readAttributes(parser))
						RECORD -> metadata.copy(record = readAttributes(parser))
						EXEMPLARS -> metadata.copy(exemplars = readArray(parser) { readAttributes(parser) })
						EVENT_NAME -> {
							expect(parser.currentToken(), JsonToken.VALUE_STRING)
							metadata.copy(eventName = parser.string)
						}
						else -> throw IllegalArgumentException("모르는 metadata 경계: $name")
					}
				}
				metadata
			}

		private fun writeSection(generator: JsonGenerator, name: String, attributes: List<TypedAttribute>) {
			generator.writeName(name)
			writeAttributes(generator, attributes)
		}

		private fun writeAttributes(generator: JsonGenerator, attributes: List<TypedAttribute>) {
			generator.writeStartArray()
			for (attribute in attributes) {
				generator.writeStartObject()
				generator.writeStringProperty("key", attribute.key)
				generator.writeName("value")
				writeValue(generator, attribute.value)
				generator.writeEndObject()
			}
			generator.writeEndArray()
		}

		private fun writeValue(generator: JsonGenerator, value: TypedValue) {
			generator.writeStartObject()
			when (value) {
				is TypedValue.Str -> generator.writeStringProperty("stringValue", value.value)
				is TypedValue.Bool -> generator.writeBooleanProperty("boolValue", value.value)
				is TypedValue.Int -> generator.writeStringProperty("intValue", value.value.toString())
				is TypedValue.Double -> {
					generator.writeName("doubleValue")
					val d = value.value
					when {
						d.isNaN() -> generator.writeString("NaN")
						d == kotlin.Double.POSITIVE_INFINITY -> generator.writeString("Infinity")
						d == kotlin.Double.NEGATIVE_INFINITY -> generator.writeString("-Infinity")
						else -> generator.writeNumber(d)
					}
				}
				is TypedValue.Bytes -> generator.writeStringProperty("bytesValue", value.base64)
				is TypedValue.Array -> {
					generator.writeName("arrayValue")
					generator.writeStartObject()
					generator.writeName("values")
					generator.writeStartArray()
					value.values.forEach { writeValue(generator, it) }
					generator.writeEndArray()
					generator.writeEndObject()
				}
				is TypedValue.KvList -> {
					generator.writeName("kvlistValue")
					generator.writeStartObject()
					generator.writeName("values")
					writeAttributes(generator, value.values)
					generator.writeEndObject()
				}
				TypedValue.Empty -> Unit
			}
			generator.writeEndObject()
		}

		private fun readAttributes(parser: JsonParser): List<TypedAttribute> = readArray(parser) {
			expect(parser.currentToken(), JsonToken.START_OBJECT)
			var key: String? = null
			var value: TypedValue? = null
			while (parser.nextToken() != JsonToken.END_OBJECT) {
				when (val name = parser.currentName()) {
					"key" -> {
						expect(parser.nextToken(), JsonToken.VALUE_STRING)
						key = parser.string
					}
					"value" -> {
						parser.nextToken()
						value = readValue(parser)
					}
					else -> throw IllegalArgumentException("KeyValue 의 모르는 필드: $name")
				}
			}
			TypedAttribute(requireNotNull(key) { "KeyValue 에 key 가 없다" }, value ?: TypedValue.Empty)
		}

		private fun readValue(parser: JsonParser): TypedValue {
			expect(parser.currentToken(), JsonToken.START_OBJECT)
			if (parser.nextToken() == JsonToken.END_OBJECT) return TypedValue.Empty
			val value = when (val name = parser.currentName()) {
				"stringValue" -> TypedValue.Str(stringOf(parser.nextToken(), parser))
				"boolValue" -> when (parser.nextToken()) {
					JsonToken.VALUE_TRUE -> TypedValue.Bool(true)
					JsonToken.VALUE_FALSE -> TypedValue.Bool(false)
					else -> throw IllegalArgumentException("boolValue 가 불리언이 아니다")
				}
				// OTLP/JSON 은 int64 를 문자열로 쓴다. 숫자로 와도 받되 Double 을 거치지 않는다.
				"intValue" -> when (parser.nextToken()) {
					JsonToken.VALUE_STRING -> TypedValue.Int(parser.string.toLong())
					JsonToken.VALUE_NUMBER_INT -> TypedValue.Int(parser.longValue)
					else -> throw IllegalArgumentException("intValue 가 정수가 아니다")
				}
				"doubleValue" -> when (val token = parser.nextToken()) {
					JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT -> TypedValue.Double(parser.doubleValue)
					JsonToken.VALUE_STRING -> TypedValue.Double(
						when (val text = parser.string) {
							"NaN" -> kotlin.Double.NaN
							"Infinity" -> kotlin.Double.POSITIVE_INFINITY
							"-Infinity" -> kotlin.Double.NEGATIVE_INFINITY
							else -> throw IllegalArgumentException("doubleValue 문자열이 아니다: $text")
						},
					)
					else -> throw IllegalArgumentException("doubleValue 토큰이 아니다: $token")
				}
				"bytesValue" -> TypedValue.Bytes(stringOf(parser.nextToken(), parser))
				"arrayValue" -> TypedValue.Array(readWrapped(parser) { readArray(parser) { readValue(parser) } })
				"kvlistValue" -> TypedValue.KvList(readWrapped(parser) { readAttributes(parser) })
				else -> throw IllegalArgumentException("AnyValue 의 모르는 필드: $name")
			}
			expect(parser.nextToken(), JsonToken.END_OBJECT)
			return value
		}

		/** `{"values": [...]}` 를 읽는다. */
		private fun <T> readWrapped(parser: JsonParser, readValues: () -> List<T>): List<T> {
			expect(parser.nextToken(), JsonToken.START_OBJECT)
			var values: List<T> = emptyList()
			while (parser.nextToken() != JsonToken.END_OBJECT) {
				require(parser.currentName() == "values") { "values 가 아닌 필드: ${parser.currentName()}" }
				parser.nextToken()
				values = readValues()
			}
			return values
		}

		private fun <T> readArray(parser: JsonParser, readElement: () -> T): List<T> {
			expect(parser.currentToken(), JsonToken.START_ARRAY)
			val out = mutableListOf<T>()
			while (parser.nextToken() != JsonToken.END_ARRAY) out += readElement()
			return out
		}

		private fun stringOf(token: JsonToken?, parser: JsonParser): String {
			expect(token, JsonToken.VALUE_STRING)
			return parser.string
		}

		private fun expect(actual: JsonToken?, expected: JsonToken) {
			require(actual == expected) { "JSON 토큰 $expected 를 기대했는데 $actual 이다" }
		}
	}
}
