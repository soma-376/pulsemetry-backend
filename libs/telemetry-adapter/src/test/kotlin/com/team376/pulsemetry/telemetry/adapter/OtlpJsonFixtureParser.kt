package com.team376.pulsemetry.telemetry.adapter

import com.google.protobuf.ByteString
import com.google.protobuf.Descriptors
import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.Message
import java.util.Base64

/**
 * OTLP/JSON 문자열을 protobuf 요청으로 읽는 **테스트 전용** 파서.
 *
 * ## 왜 여기 있나
 *
 * 운영 경로에서 이 일을 하는 것은 수집 모듈이다. 그 코덱은 그쪽 `internal` 이고, 단계 모듈끼리
 * 직접 참조하지 않기로 했으므로(ADR 0010·0011) 여기서 fixture 를 읽을 만큼만 따로 둔다.
 * **테스트 소스셋 밖으로 내보내지 마라** — 상호운용의 진실원은 수집 모듈의 코덱이다.
 *
 * [ProtoJson] 의 역방향이고 같은 규칙을 쓴다 — camelCase·snake_case 를 모두 받고, 64비트
 * 정수는 문자열, enum 은 숫자, ID bytes 는 hex 다. 모르는 필드는 건너뛴다.
 */
internal object OtlpJsonFixtureParser {

	private val HEX_ID_FIELDS = setOf("trace_id", "span_id", "parent_span_id", "profile_id")

	/** JSON 트리를 빌더에 채운다. */
	fun merge(tree: Map<String, Any?>, builder: Message.Builder) {
		val descriptor = builder.descriptorForType
		for ((name, value) in tree) {
			val field = findField(descriptor, name) ?: continue
			if (value == null) continue
			if (field.isRepeated) {
				for (item in value as List<*>) {
					builder.addRepeatedField(field, readValue(field, builder, item!!))
				}
			} else {
				builder.setField(field, readValue(field, builder, value))
			}
		}
	}

	private fun findField(descriptor: Descriptors.Descriptor, name: String): FieldDescriptor? =
		descriptor.findFieldByName(name) ?: descriptor.fields.firstOrNull { it.jsonName == name }

	private fun readValue(field: FieldDescriptor, parent: Message.Builder, value: Any): Any =
		when (field.type) {
			FieldDescriptor.Type.MESSAGE, FieldDescriptor.Type.GROUP -> {
				val sub = parent.newBuilderForField(field)
				@Suppress("UNCHECKED_CAST")
				merge(value as Map<String, Any?>, sub)
				sub.build()
			}

			FieldDescriptor.Type.ENUM -> when (value) {
				is Number -> field.enumType.findValueByNumberCreatingIfUnknown(value.toInt())
				else -> field.enumType.findValueByName(value as String)
					?: error("모르는 enum 값이다: $value (${field.fullName})")
			}

			FieldDescriptor.Type.BYTES ->
				if (field.name in HEX_ID_FIELDS) {
					fromHex(value as String)
				} else {
					ByteString.copyFrom(Base64.getDecoder().decode(value as String))
				}

			FieldDescriptor.Type.INT64, FieldDescriptor.Type.SINT64,
			FieldDescriptor.Type.SFIXED64, FieldDescriptor.Type.UINT64,
			FieldDescriptor.Type.FIXED64 -> asLong(value)

			FieldDescriptor.Type.INT32, FieldDescriptor.Type.SINT32,
			FieldDescriptor.Type.SFIXED32, FieldDescriptor.Type.UINT32,
			FieldDescriptor.Type.FIXED32 -> asLong(value).toInt()

			FieldDescriptor.Type.DOUBLE -> asDouble(value)
			FieldDescriptor.Type.FLOAT -> asDouble(value).toFloat()
			FieldDescriptor.Type.BOOL -> value as? Boolean ?: (value as String).toBoolean()
			FieldDescriptor.Type.STRING -> value as String
		}

	private fun asLong(value: Any): Long = when (value) {
		is Number -> value.toLong()
		else -> (value as String).trim().toLong()
	}

	private fun asDouble(value: Any): Double = when (value) {
		is Number -> value.toDouble()
		else -> when (val text = (value as String).trim()) {
			"NaN" -> Double.NaN
			"Infinity", "+Infinity" -> Double.POSITIVE_INFINITY
			"-Infinity" -> Double.NEGATIVE_INFINITY
			else -> text.toDouble()
		}
	}

	private fun fromHex(hex: String): ByteString {
		require(hex.length % 2 == 0) { "hex 길이가 홀수다: ${hex.length}" }
		val out = ByteArray(hex.length / 2)
		for (index in out.indices) {
			val high = Character.digit(hex[index * 2], 16)
			val low = Character.digit(hex[index * 2 + 1], 16)
			require(high >= 0 && low >= 0) { "hex 가 아닌 문자가 있다: \"$hex\"" }
			out[index] = ((high shl 4) or low).toByte()
		}
		return ByteString.copyFrom(out)
	}
}
