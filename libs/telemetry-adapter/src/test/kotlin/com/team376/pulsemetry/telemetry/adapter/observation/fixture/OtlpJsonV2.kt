package com.team376.pulsemetry.telemetry.adapter.observation.fixture

import com.google.protobuf.ByteString
import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.Message
import java.util.Base64

/**
 * OTLP/JSON 트리를 protobuf 로 읽는 **otlp-v2 fixture 전용** 파서. 운영 경로에서 이 일은 수집 모듈의 코덱이 한다.
 *
 * OTLP/JSON 규칙: camelCase 필드 이름(snake_case 도 받는다), 64비트 정수는 문자열(부호 없는 타입은 UInt64 전 범위),
 * enum 은 숫자 또는 이름, trace/span ID 는 hex, 그 밖의 bytes 는 base64, 비유한 double 은 "NaN"·"Infinity"·"-Infinity".
 * **모르는 필드는 실패한다** — 손으로 쓴 입력의 오타가 조용히 사라지지 않게 한다.
 */
object OtlpJsonV2 {

	private val HEX_ID_FIELDS = setOf("trace_id", "span_id", "parent_span_id")

	fun merge(tree: Map<*, *>, builder: Message.Builder) {
		val descriptor = builder.descriptorForType
		for ((name, value) in tree) {
			val field = descriptor.findFieldByName(name as String) ?: descriptor.fields.firstOrNull { it.jsonName == name }
				?: throw IllegalArgumentException("${descriptor.fullName} 에 없는 필드: $name")
			if (value == null) continue
			if (field.isRepeated) {
				(value as List<*>).forEach { builder.addRepeatedField(field, read(field, builder, it!!)) }
			} else {
				builder.setField(field, read(field, builder, value))
			}
		}
	}

	private fun read(field: FieldDescriptor, parent: Message.Builder, value: Any): Any = when (field.type) {
		FieldDescriptor.Type.MESSAGE, FieldDescriptor.Type.GROUP -> parent.newBuilderForField(field).also { merge(value as Map<*, *>, it) }.build()
		FieldDescriptor.Type.ENUM -> when (value) {
			is JsonNumber -> field.enumType.findValueByNumberCreatingIfUnknown(value.text.toInt())
			else -> field.enumType.findValueByName(value as String) ?: throw IllegalArgumentException("모르는 enum: $value")
		}
		FieldDescriptor.Type.BYTES -> if (field.name in HEX_ID_FIELDS) hex(value as String) else ByteString.copyFrom(Base64.getDecoder().decode(value as String))
		FieldDescriptor.Type.UINT64, FieldDescriptor.Type.FIXED64 -> text(value).toULong().toLong()
		FieldDescriptor.Type.INT64, FieldDescriptor.Type.SINT64, FieldDescriptor.Type.SFIXED64 -> text(value).toLong()
		FieldDescriptor.Type.UINT32, FieldDescriptor.Type.FIXED32 -> text(value).toUInt().toInt()
		FieldDescriptor.Type.INT32, FieldDescriptor.Type.SINT32, FieldDescriptor.Type.SFIXED32 -> text(value).toInt()
		FieldDescriptor.Type.DOUBLE -> double(value)
		FieldDescriptor.Type.FLOAT -> double(value).toFloat()
		FieldDescriptor.Type.BOOL -> value as Boolean
		FieldDescriptor.Type.STRING -> value as String
	}

	private fun text(value: Any): String = when (value) {
		is JsonNumber -> value.text
		is String -> value
		else -> throw IllegalArgumentException("정수가 아니다: $value")
	}

	private fun double(value: Any): Double = when (value) {
		is JsonNumber -> value.text.toDouble()
		"NaN" -> Double.NaN
		"Infinity" -> Double.POSITIVE_INFINITY
		"-Infinity" -> Double.NEGATIVE_INFINITY
		else -> throw IllegalArgumentException("double 이 아니다: $value")
	}

	private fun hex(text: String): ByteString {
		require(text.length % 2 == 0) { "hex 길이가 홀수다: $text" }
		return ByteString.copyFrom(ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
	}
}
