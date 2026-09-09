package com.team376.pulsemetry.telemetry.collector

import com.google.protobuf.ByteString
import com.google.protobuf.Descriptors
import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.Message
import com.google.protobuf.MessageOrBuilder
import tools.jackson.core.json.JsonFactory
import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * OTLP/JSON 코덱. 상위 Go `pdata/internal/json` 과 각 메시지의 `MarshalJSON`/`UnmarshalJSON` 이식.
 *
 * 출력 바이트는 이 모듈의 테스트가 고정한다 — 성공·상태 본문은 `OtlpResponsesTest`,
 * 실측 캡처 48문서의 왕복은 `OtlpIngestHandlerTest`. Jackson 3(`tools.jackson`, Boot 가
 * 관리하는 버전)의 스트리밍 API 만 쓴다(출처: 형제 체크아웃 `otelcol-kotlin` 의 `OtlpJson.kt`).
 *
 * ## `com.google.protobuf.util.JsonFormat` 을 쓸 수 없는 이유
 *
 * | 규칙 | OTLP/JSON (= Go pdata) | `JsonFormat` 기본 |
 * |---|---|---|
 * | `trace_id`·`span_id`·`parent_span_id` | **hex 문자열** | base64 |
 * | enum | **정수**로 쓰기, 읽을 땐 정수·이름 모두 | 이름으로 쓰기 |
 * | 64비트 정수 | 10진 문자열 | 10진 문자열 (일치) |
 * | 필드명 | camelCase 쓰기, camel+snake 읽기 | 동일 (일치) |
 * | double 특수값 | `"NaN"`·`"Infinity"`·`"-Infinity"` | 동일 (일치) |
 *
 * 앞의 두 항목이 어긋나면 상호운용이 그대로 깨진다. **`JsonFormat` 으로 바꾸지 마라.**
 *
 * ## 필드 기록 규칙 (Go 와 동일)
 *
 * - 스칼라: 기본값이면 생략
 * - repeated: 비어 있으면 생략
 * - oneof 멤버 · proto3 optional: 설정돼 있을 때만
 * - **단일 메시지 필드(비 oneof): 비어 있어도 항상 기록** — Go(gogo non-nullable)가 그렇게 한다.
 *   빈 `ExportLogsServiceResponse` 가 `{}` 가 아니라 `{"partialSuccess":{}}` 인 이유가 이것이다
 *   ([OtlpResponses] 참고).
 * - ID: 전부 0 이면 생략 (Go `IsEmpty`)
 */
internal object OtlpJson {

	private val factory = JsonFactory()

	/** hex 로 인코딩하는 bytes 필드 이름. 나머지 bytes 는 base64 다. */
	private val HEX_ID_FIELDS = setOf("trace_id", "span_id", "parent_span_id", "profile_id")

	/**
	 * Go 와 필드 순서가 어긋나는 메시지의 순서 오버라이드.
	 *
	 * Go pdatagen 은 모델을 손으로 선언하는데 `Exemplar` 만 `.proto` 선언 순서
	 * (`span_id = 4`, `trace_id = 5`)가 아니라 `TraceId`, `SpanId` 순으로 적혀 있다.
	 * 순서가 어긋나는 곳은 상위 전체에서 여기 하나뿐이다.
	 */
	private val FIELD_ORDER_OVERRIDE: Map<String, List<String>> = mapOf(
		"opentelemetry.proto.metrics.v1.Exemplar" to
			listOf("filtered_attributes", "time_unix_nano", "as_double", "as_int", "trace_id", "span_id"),
	)

	private val orderCache = ConcurrentHashMap<String, List<FieldDescriptor>>()

	/** 폐기된 scope 필드의 JSON 키 -> 이관 대상 필드 이름. Go `MigrateTraces` 등에 대응한다. */
	private val DEPRECATED_SCOPE_KEYS = mapOf(
		"deprecatedScopeSpans" to "scope_spans",
		"deprecated_scope_spans" to "scope_spans",
		"deprecatedScopeLogs" to "scope_logs",
		"deprecated_scope_logs" to "scope_logs",
		"deprecatedScopeMetrics" to "scope_metrics",
		"deprecated_scope_metrics" to "scope_metrics",
	)

	// ------------------------------------------------------------------ 쓰기

	fun toJson(message: MessageOrBuilder): ByteArray {
		val out = ByteArrayOutputStream()
		factory.createGenerator(out).use { write(it, message) }
		return out.toByteArray()
	}

	fun write(gen: JsonGenerator, message: MessageOrBuilder) {
		gen.writeStartObject()
		for (field in fieldsInWriteOrder(message.descriptorForType)) {
			if (field.isRepeated) {
				val count = message.getRepeatedFieldCount(field)
				if (count == 0) continue
				gen.writeName(field.jsonName)
				gen.writeStartArray()
				for (i in 0 until count) writeValue(gen, field, message.getRepeatedField(field, i))
				gen.writeEndArray()
			} else {
				if (!isPresent(message, field)) continue
				gen.writeName(field.jsonName)
				writeValue(gen, field, message.getField(field))
			}
		}
		gen.writeEndObject()
	}

	private fun fieldsInWriteOrder(descriptor: Descriptors.Descriptor): List<FieldDescriptor> =
		orderCache.computeIfAbsent(descriptor.fullName) {
			val override = FIELD_ORDER_OVERRIDE[descriptor.fullName]
				?: return@computeIfAbsent descriptor.fields
			val byName = descriptor.fields.associateBy { f -> f.name }
			val ordered = override.mapNotNull { name -> byName[name] }
			// 오버라이드에 없는 필드는 선언 순서대로 뒤에 붙인다.
			ordered + descriptor.fields.filter { f -> f.name !in override }
		}

	private fun isPresent(message: MessageOrBuilder, field: FieldDescriptor): Boolean {
		if (field.realContainingOneof != null) return message.hasField(field)
		if (field.toProto().proto3Optional) return message.hasField(field)
		// Go 는 non-nullable 임베디드 메시지를 비어 있어도 항상 쓴다.
		if (field.javaType == FieldDescriptor.JavaType.MESSAGE) return true
		val value = message.getField(field)
		if (field.type == FieldDescriptor.Type.BYTES && field.name in HEX_ID_FIELDS) {
			return !isAllZero(value as ByteString)
		}
		return value != field.defaultValue
	}

	private fun isAllZero(bytes: ByteString): Boolean {
		for (i in 0 until bytes.size()) if (bytes.byteAt(i) != 0.toByte()) return false
		return true
	}

	private fun writeValue(gen: JsonGenerator, field: FieldDescriptor, value: Any) {
		when (field.type) {
			FieldDescriptor.Type.MESSAGE, FieldDescriptor.Type.GROUP ->
				write(gen, value as MessageOrBuilder)

			FieldDescriptor.Type.ENUM ->
				gen.writeNumber((value as Descriptors.EnumValueDescriptor).number)

			FieldDescriptor.Type.BYTES -> {
				val bytes = value as ByteString
				if (field.name in HEX_ID_FIELDS) {
					gen.writeString(toHex(bytes))
				} else {
					gen.writeString(
						if (bytes.isEmpty) "" else Base64.getEncoder().encodeToString(bytes.toByteArray()),
					)
				}
			}

			// 64비트 정수는 10진 문자열이다. proto3 JSON 규칙.
			FieldDescriptor.Type.INT64, FieldDescriptor.Type.SINT64, FieldDescriptor.Type.SFIXED64 ->
				gen.writeString((value as Long).toString())

			FieldDescriptor.Type.UINT64, FieldDescriptor.Type.FIXED64 ->
				gen.writeString(java.lang.Long.toUnsignedString(value as Long))

			FieldDescriptor.Type.UINT32, FieldDescriptor.Type.FIXED32 ->
				gen.writeNumber(java.lang.Integer.toUnsignedLong(value as Int))

			FieldDescriptor.Type.INT32, FieldDescriptor.Type.SINT32, FieldDescriptor.Type.SFIXED32 ->
				gen.writeNumber(value as Int)

			FieldDescriptor.Type.DOUBLE -> writeDouble(gen, value as Double)
			FieldDescriptor.Type.FLOAT -> writeDouble(gen, (value as Float).toDouble())
			FieldDescriptor.Type.BOOL -> gen.writeBoolean(value as Boolean)
			FieldDescriptor.Type.STRING -> gen.writeString(value as String)
		}
	}

	private fun writeDouble(gen: JsonGenerator, value: Double) {
		when {
			value.isNaN() -> gen.writeString("NaN")
			value == Double.POSITIVE_INFINITY -> gen.writeString("Infinity")
			value == Double.NEGATIVE_INFINITY -> gen.writeString("-Infinity")
			else -> gen.writeNumber(formatDoubleLikeGoJson(value))
		}
	}

	/**
	 * Go `encoding/json` 과 같은 실수 표기.
	 *
	 * |v| 가 1e-6 미만이거나 1e21 이상이면 지수 표기, 아니면 소수 표기다.
	 * 지수부에 앞자리 0 을 붙이지 않는다 — `1e-7` 이지 `1e-07` 이 아니다.
	 */
	internal fun formatDoubleLikeGoJson(value: Double): String {
		if (value == 0.0) return if (1.0 / value < 0) "-0" else "0"
		val abs = Math.abs(value)
		if (abs < 1e-6 || abs >= 1e21) {
			val s = java.lang.Double.toString(value)
			val idx = s.indexOf('E')
			if (idx < 0) return s
			var mantissa = s.substring(0, idx)
			if (mantissa.endsWith(".0")) mantissa = mantissa.dropLast(2)
			val exp = s.substring(idx + 1)
			return if (exp.startsWith("-")) "${mantissa}e-${exp.substring(1)}" else "${mantissa}e+$exp"
		}
		return BigDecimal(java.lang.Double.toString(value)).stripTrailingZeros().toPlainString()
	}

	private fun toHex(bytes: ByteString): String {
		val hex = "0123456789abcdef"
		val out = CharArray(bytes.size() * 2)
		for (i in 0 until bytes.size()) {
			val v = bytes.byteAt(i).toInt() and 0xFF
			out[i * 2] = hex[v ushr 4]
			out[i * 2 + 1] = hex[v and 0x0F]
		}
		return String(out)
	}

	// ------------------------------------------------------------------ 읽기

	/**
	 * 모르는 필드는 **건너뛴다**(상위 `HandleUnknownField` 기본값). 엄격 모드는 테스트용이다 —
	 * 수신 경로에서 켜면 상위 프로토콜이 필드를 늘릴 때 클라이언트가 전부 400 을 받는다.
	 */
	fun fromJson(bytes: ByteArray, builder: Message.Builder, disallowUnknownFields: Boolean = false) {
		factory.createParser(bytes).use { parser ->
			require(parser.nextToken() == JsonToken.START_OBJECT) { "최상위가 JSON 객체가 아니다" }
			read(parser, builder, disallowUnknownFields)
		}
	}

	fun read(parser: JsonParser, builder: Message.Builder, disallowUnknownFields: Boolean = false) {
		val descriptor = builder.descriptorForType
		// 폐기된 scope 키로 들어온 항목. 객체를 다 읽은 뒤 새 필드가 비어 있을 때만 옮긴다.
		var deprecated: MutableList<Message>? = null
		var deprecatedTarget: FieldDescriptor? = null

		while (parser.nextToken() != JsonToken.END_OBJECT) {
			val name = parser.currentName()
			parser.nextToken()

			val field = findField(descriptor, name)
			if (field != null) {
				readField(parser, builder, field, disallowUnknownFields)
				continue
			}

			val migrateTo = DEPRECATED_SCOPE_KEYS[name]?.let { descriptor.findFieldByName(it) }
			if (migrateTo != null) {
				deprecatedTarget = migrateTo
				val list = deprecated ?: mutableListOf<Message>().also { deprecated = it }
				if (parser.currentToken() == JsonToken.START_ARRAY) {
					while (parser.nextToken() != JsonToken.END_ARRAY) {
						val sub = builder.newBuilderForField(migrateTo)
						read(parser, sub, disallowUnknownFields)
						list += sub.build()
					}
				}
				continue
			}

			if (disallowUnknownFields) {
				throw IllegalArgumentException("모르는 필드 \"$name\" (${descriptor.fullName})")
			}
			parser.skipChildren()
		}

		val target = deprecatedTarget
		val pending = deprecated
		if (target != null && pending != null && builder.getRepeatedFieldCount(target) == 0) {
			pending.forEach { builder.addRepeatedField(target, it) }
		}
	}

	/** camelCase 와 snake_case 를 모두 받는다. Go 의 `case "scopeLogs", "scope_logs":` 와 같다. */
	private fun findField(descriptor: Descriptors.Descriptor, name: String): FieldDescriptor? =
		descriptor.findFieldByName(name) ?: descriptor.fields.firstOrNull { it.jsonName == name }

	private fun readField(
		parser: JsonParser,
		builder: Message.Builder,
		field: FieldDescriptor,
		strict: Boolean,
	) {
		if (field.isRepeated) {
			if (parser.currentToken() == JsonToken.VALUE_NULL) return
			require(parser.currentToken() == JsonToken.START_ARRAY) {
				"repeated 필드 ${field.fullName} 자리에 배열이 아닌 값이 왔다"
			}
			while (parser.nextToken() != JsonToken.END_ARRAY) {
				builder.addRepeatedField(field, readValue(parser, builder, field, strict))
			}
			return
		}
		if (parser.currentToken() == JsonToken.VALUE_NULL) return

		val value = readValue(parser, builder, field, strict)

		// 쓰기가 Go 와 같이 단일 메시지 필드를 항상 내보내므로(`"scope":{}`), 읽기에서 그것을
		// 그대로 present 로 만들면 JSON 왕복이 proto 바이트를 늘린다. 비어 있는 단일 메시지는
		// 설정하지 않아 proto 를 정규형으로 유지한다.
		// oneof 멤버는 present 자체가 의미이므로(예: `metric.gauge = {}`) 예외다.
		if (field.javaType == FieldDescriptor.JavaType.MESSAGE &&
			field.realContainingOneof == null &&
			!field.toProto().proto3Optional &&
			(value as Message).serializedSize == 0
		) {
			return
		}

		builder.setField(field, value)
	}

	private fun readValue(
		parser: JsonParser,
		builder: Message.Builder,
		field: FieldDescriptor,
		strict: Boolean,
	): Any = when (field.type) {
		FieldDescriptor.Type.MESSAGE, FieldDescriptor.Type.GROUP -> {
			val sub = builder.newBuilderForField(field)
			read(parser, sub, strict)
			sub.build()
		}

		FieldDescriptor.Type.ENUM -> readEnum(parser, field)

		FieldDescriptor.Type.BYTES ->
			if (field.name in HEX_ID_FIELDS) {
				fromHex(parser.getString())
			} else {
				ByteString.copyFrom(Base64.getDecoder().decode(parser.getString()))
			}

		FieldDescriptor.Type.INT64, FieldDescriptor.Type.SINT64, FieldDescriptor.Type.SFIXED64 ->
			readLong(parser)

		FieldDescriptor.Type.UINT64, FieldDescriptor.Type.FIXED64 ->
			if (parser.currentToken() == JsonToken.VALUE_STRING) {
				java.lang.Long.parseUnsignedLong(parser.getString().trim())
			} else {
				parser.longValue
			}

		FieldDescriptor.Type.UINT32, FieldDescriptor.Type.FIXED32 -> readLong(parser).toInt()
		FieldDescriptor.Type.INT32, FieldDescriptor.Type.SINT32, FieldDescriptor.Type.SFIXED32 ->
			readLong(parser).toInt()

		FieldDescriptor.Type.DOUBLE -> readDouble(parser)
		FieldDescriptor.Type.FLOAT -> readDouble(parser).toFloat()

		FieldDescriptor.Type.BOOL ->
			if (parser.currentToken() == JsonToken.VALUE_STRING) {
				parser.getString().toBoolean()
			} else {
				parser.booleanValue
			}

		FieldDescriptor.Type.STRING -> parser.getString()
	}

	/** 정수와 이름을 모두 받는다. Go 의 enum 읽기와 같다. */
	private fun readEnum(parser: JsonParser, field: FieldDescriptor): Descriptors.EnumValueDescriptor =
		if (parser.currentToken() == JsonToken.VALUE_STRING) {
			val text = parser.getString()
			field.enumType.findValueByName(text)
				?: text.toIntOrNull()?.let { field.enumType.findValueByNumberCreatingIfUnknown(it) }
				?: throw IllegalArgumentException("모르는 enum 값 \"$text\" (${field.fullName})")
		} else {
			field.enumType.findValueByNumberCreatingIfUnknown(parser.intValue)
		}

	/** 숫자와 문자열을 모두 받는다. Go 의 `ReadInt64` 등과 같다. */
	private fun readLong(parser: JsonParser): Long =
		if (parser.currentToken() == JsonToken.VALUE_STRING) parser.getString().trim().toLong() else parser.longValue

	private fun readDouble(parser: JsonParser): Double =
		if (parser.currentToken() == JsonToken.VALUE_STRING) {
			when (val t = parser.getString().trim()) {
				"NaN" -> Double.NaN
				"Infinity", "+Infinity" -> Double.POSITIVE_INFINITY
				"-Infinity" -> Double.NEGATIVE_INFINITY
				else -> t.toDouble()
			}
		} else {
			parser.doubleValue
		}

	private fun fromHex(hex: String): ByteString {
		if (hex.isEmpty()) return ByteString.EMPTY
		require(hex.length % 2 == 0) { "hex 길이가 홀수다: ${hex.length}" }
		val out = ByteArray(hex.length / 2)
		for (i in out.indices) {
			val hi = Character.digit(hex[i * 2], 16)
			val lo = Character.digit(hex[i * 2 + 1], 16)
			require(hi >= 0 && lo >= 0) { "hex 가 아닌 문자가 있다: \"$hex\"" }
			out[i] = ((hi shl 4) or lo).toByte()
		}
		return ByteString.copyFrom(out)
	}
}
