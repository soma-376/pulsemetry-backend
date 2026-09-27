package com.team376.pulsemetry.telemetry.adapter.observation

import com.google.protobuf.ByteString
import com.google.protobuf.Descriptors.EnumValueDescriptor
import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.MessageOrBuilder
import java.security.MessageDigest
import java.util.Base64

/**
 * 관측 ID·series ID·analysis_hash 가 공유하는 canonical 표기(ADR 0020 §2).
 *
 * 출력은 JSON 모양의 텍스트이고, 같은 의미는 언제나 같은 바이트다.
 *
 * - **protobuf 의미를 먼저 읽는다.** 입력은 이미 protobuf 메시지다 — OTLP/JSON 과 protobuf 의 숫자 표기·필드
 *   순서 차이는 여기 오기 전에 사라진다. 필드는 [MessageOrBuilder.getAllFields] 가 주는 것만 쓴다: proto3 의
 *   non-optional 스칼라는 기본값이면 빠지고(표현 차이 제거), optional·oneof·메시지 필드는 기본값이어도 존재가
 *   남는다.
 * - 객체 필드는 proto 이름순이다. 숫자는 손실 없는 10진이다 — 부호 없는 정수는 부호 없이, double 은 JVM 의
 *   최단 왕복 표기, 비유한 값은 `"NaN"`·`"Infinity"`·`"-Infinity"` 문자열이다. bytes 는 base64, enum 은 번호다.
 * - **OTLP `KeyValue` 배열은 원소의 canonical 텍스트로 정렬하되 중복을 지우지 않는다.** 그 밖의 반복 필드
 *   (`arrayValue`·span event·histogram 버킷 등)는 순서가 의미라 그대로 둔다.
 */
internal object Canonical {

	private const val KEY_VALUE = "opentelemetry.proto.common.v1.KeyValue"

	fun encode(message: MessageOrBuilder): String = StringBuilder().also { writeMessage(it, message) }.toString()

	fun sha256Hex(text: String): String =
		MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
			.joinToString("") { "%02x".format(it) }

	private fun writeMessage(out: StringBuilder, message: MessageOrBuilder) {
		out.append('{')
		message.allFields.entries.sortedBy { it.key.name }.forEachIndexed { index, (field, value) ->
			if (index > 0) out.append(',')
			writeString(out, field.name)
			out.append(':')
			writeField(out, field, value)
		}
		out.append('}')
	}

	private fun writeField(out: StringBuilder, field: FieldDescriptor, value: Any) {
		if (!field.isRepeated) {
			writeSingle(out, field, value)
			return
		}
		val elements = (value as List<*>).map { element -> StringBuilder().also { writeSingle(it, field, element!!) }.toString() }
		val ordered = if (field.javaType == FieldDescriptor.JavaType.MESSAGE && field.messageType.fullName == KEY_VALUE) {
			elements.sorted()
		} else {
			elements
		}
		ordered.joinTo(out, separator = ",", prefix = "[", postfix = "]")
	}

	private fun writeSingle(out: StringBuilder, field: FieldDescriptor, value: Any) {
		when (field.javaType) {
			FieldDescriptor.JavaType.INT -> out.append(
				if (isUnsigned(field)) Integer.toUnsignedString(value as Int) else (value as Int).toString(),
			)
			FieldDescriptor.JavaType.LONG -> out.append(
				if (isUnsigned(field)) java.lang.Long.toUnsignedString(value as Long) else (value as Long).toString(),
			)
			FieldDescriptor.JavaType.FLOAT -> writeNumber(out, (value as Float).toDouble())
			FieldDescriptor.JavaType.DOUBLE -> writeNumber(out, value as Double)
			FieldDescriptor.JavaType.BOOLEAN -> out.append(value as Boolean)
			FieldDescriptor.JavaType.STRING -> writeString(out, value as String)
			FieldDescriptor.JavaType.BYTE_STRING -> writeString(out, Base64.getEncoder().encodeToString((value as ByteString).toByteArray()))
			FieldDescriptor.JavaType.ENUM -> out.append((value as EnumValueDescriptor).number)
			FieldDescriptor.JavaType.MESSAGE -> writeMessage(out, value as MessageOrBuilder)
			null -> error("타입 없는 필드: ${field.fullName}")
		}
	}

	private fun isUnsigned(field: FieldDescriptor): Boolean =
		field.type == FieldDescriptor.Type.UINT32 || field.type == FieldDescriptor.Type.FIXED32 ||
			field.type == FieldDescriptor.Type.UINT64 || field.type == FieldDescriptor.Type.FIXED64

	fun writeNumber(out: StringBuilder, value: Double) {
		when {
			value.isNaN() -> writeString(out, "NaN")
			value == Double.POSITIVE_INFINITY -> writeString(out, "Infinity")
			value == Double.NEGATIVE_INFINITY -> writeString(out, "-Infinity")
			else -> out.append(value.toString())
		}
	}

	fun writeString(out: StringBuilder, value: String) {
		out.append('"')
		for (ch in value) {
			when {
				ch == '"' -> out.append("\\\"")
				ch == '\\' -> out.append("\\\\")
				ch < ' ' -> out.append("\\u").append("%04x".format(ch.code))
				else -> out.append(ch)
			}
		}
		out.append('"')
	}
}

/** 64자리 소문자 hex — `FixedString(64)` 컬럼의 값(ADR 0020 §1). 길이는 바이너리 digest 가 아니라 ASCII hex 다. */
public object Hex64 {
	private val PATTERN = Regex("^[0-9a-f]{64}$")

	public fun isValid(value: String): Boolean = PATTERN.matches(value)

	public fun require(column: String, value: String) {
		require(isValid(value)) { "$column 은 64자리 소문자 hex 다: $value" }
	}
}
