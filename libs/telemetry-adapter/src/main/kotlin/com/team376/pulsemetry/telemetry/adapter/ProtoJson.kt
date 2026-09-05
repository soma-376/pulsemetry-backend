package com.team376.pulsemetry.telemetry.adapter

import com.google.protobuf.ByteString
import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.MessageOrBuilder

/**
 * protobuf 메시지를 OTLP/JSON 모양의 네이티브 트리로 편다.
 *
 * ## 왜 있나
 *
 * 이 모듈의 입력은 수집 단계가 넘겨주는 protobuf 다. 그런데 `_ingest.source_record_id` 는
 * **원본 레코드 JSON 의 해시**라, protobuf 에서 그 JSON 을 다시 뽑아야 한다
 * ([SourceRecordId] 참고).
 *
 * ## 기록 규칙 — 수집 모듈의 코덱과 다른 곳이 하나 있다
 *
 * 수집 모듈의 OTLP/JSON 코덱은 빈 메시지 필드도 쓴다(`"body":{}`). 여기서는 **쓰지 않는다.**
 * 목적이 다르기 때문이다 — 저쪽은 아카이브 문서의 표기가 고정되어야 하고, 이쪽은
 * **클라이언트가 보낸 원본 문서와 같아야 한다.** 실제 exporter 는 값이 없는 필드를 아예
 * 싣지 않으므로, 기본값을 전부 생략하는 쪽이 원본에 가깝다.
 *
 * 나머지는 OTLP/JSON 규격 그대로다.
 *
 * | 항목 | 규칙 |
 * |---|---|
 * | 필드명 | camelCase (`jsonName`) |
 * | 64비트 정수 | **10진 문자열** |
 * | 32비트 정수 | 숫자 |
 * | enum | 숫자 |
 * | `trace_id`·`span_id`·`parent_span_id` | hex 문자열 |
 * | 그 밖의 bytes | base64 |
 * | 기본값 스칼라 · 빈 repeated · 빈 메시지 | 생략 |
 *
 * ## 한계 — 되돌릴 수 없는 정보가 있다
 *
 * 클라이언트가 **기본값을 명시적으로 실어 보내면**(`"droppedAttributesCount":0`) protobuf 가
 * 그것을 떨어뜨려 여기서 되살릴 수 없다. 기본값을 명시한 문서는 `source_record_id` 가 갈린다.
 * optional·oneof 가 아닌 스칼라 필드(`count`·`is_monotonic`·`aggregation_temporality`·
 * `timeUnixNano`·`startTimeUnixNano` 등)의 명시 기본값은 protobuf 가 부재와 구별하지 못하므로
 * **출력값과 `record_id` 도 갈릴 수 있다**(ADR 0013).
 */
internal object ProtoJson {

	/** hex 로 쓰는 bytes 필드. 나머지 bytes 는 base64 다. */
	private val HEX_ID_FIELDS = setOf("trace_id", "span_id", "parent_span_id", "profile_id")

	/** 메시지 하나를 `Map<String, Any?>` 로 편다. 값은 String·Int·Long·Double·Boolean·List·Map 뿐이다. */
	fun toTree(message: MessageOrBuilder): Map<String, Any?> {
		val out = LinkedHashMap<String, Any?>()
		for (field in message.descriptorForType.fields) {
			if (field.isRepeated) {
				val count = message.getRepeatedFieldCount(field)
				if (count == 0) continue
				out[field.jsonName] = (0 until count).map { index ->
					value(field, message.getRepeatedField(field, index))
				}
				continue
			}
			if (!isPresent(message, field)) continue
			out[field.jsonName] = value(field, message.getField(field))
		}
		return out
	}

	private fun isPresent(message: MessageOrBuilder, field: FieldDescriptor): Boolean {
		if (field.realContainingOneof != null) return message.hasField(field)
		if (field.toProto().proto3Optional) return message.hasField(field)
		if (field.javaType == FieldDescriptor.JavaType.MESSAGE) {
			// 비어 있는 메시지는 원본에도 없었을 것이다 — 위 KDoc 의 그 한 곳.
			return message.hasField(field) &&
				(message.getField(field) as com.google.protobuf.Message).serializedSize > 0
		}
		val raw = message.getField(field)
		if (field.type == FieldDescriptor.Type.BYTES && field.name in HEX_ID_FIELDS) {
			return !isAllZero(raw as ByteString)
		}
		return raw != field.defaultValue
	}

	private fun isAllZero(bytes: ByteString): Boolean {
		for (index in 0 until bytes.size()) if (bytes.byteAt(index) != 0.toByte()) return false
		return true
	}

	private fun value(field: FieldDescriptor, raw: Any): Any? = when (field.type) {
		FieldDescriptor.Type.MESSAGE, FieldDescriptor.Type.GROUP ->
			toTree(raw as MessageOrBuilder)

		FieldDescriptor.Type.ENUM ->
			(raw as com.google.protobuf.Descriptors.EnumValueDescriptor).number

		FieldDescriptor.Type.BYTES -> {
			val bytes = raw as ByteString
			if (field.name in HEX_ID_FIELDS) {
				toHex(bytes)
			} else {
				java.util.Base64.getEncoder().encodeToString(bytes.toByteArray())
			}
		}

		// 64비트 정수는 10진 문자열이다. proto3 JSON 규칙.
		FieldDescriptor.Type.INT64, FieldDescriptor.Type.SINT64, FieldDescriptor.Type.SFIXED64 ->
			(raw as Long).toString()

		FieldDescriptor.Type.UINT64, FieldDescriptor.Type.FIXED64 ->
			java.lang.Long.toUnsignedString(raw as Long)

		FieldDescriptor.Type.UINT32, FieldDescriptor.Type.FIXED32 ->
			java.lang.Integer.toUnsignedLong(raw as Int)

		FieldDescriptor.Type.INT32, FieldDescriptor.Type.SINT32, FieldDescriptor.Type.SFIXED32 ->
			raw as Int

		FieldDescriptor.Type.DOUBLE -> raw as Double
		FieldDescriptor.Type.FLOAT -> (raw as Float).toDouble()
		FieldDescriptor.Type.BOOL -> raw as Boolean
		FieldDescriptor.Type.STRING -> raw as String
	}

	private fun toHex(bytes: ByteString): String {
		val digits = "0123456789abcdef"
		val out = CharArray(bytes.size() * 2)
		for (index in 0 until bytes.size()) {
			val byte = bytes.byteAt(index).toInt() and 0xFF
			out[index * 2] = digits[byte ushr 4]
			out[index * 2 + 1] = digits[byte and 0x0F]
		}
		return String(out)
	}
}
