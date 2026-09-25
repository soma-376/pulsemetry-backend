package com.team376.pulsemetry.telemetry.adapter.observation

import com.google.protobuf.ByteString
import com.google.protobuf.Descriptors.EnumValueDescriptor
import com.google.protobuf.MessageOrBuilder

/**
 * OTLP 요청을 descriptor 로 읽는 얇은 도구. 이 모듈의 main 은 OTLP 생성 클래스에 의존하지 않는다 —
 * 진입점이 `MessageOrBuilder` 이고 필드는 proto 이름으로 찾는다(ADR 0013 의 입력 결정 유지).
 */
internal object Otlp {

	private const val LOGS = "opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest"
	private const val TRACES = "opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest"
	private const val METRICS = "opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest"

	fun signalOf(request: MessageOrBuilder): ObservationSignal? = when (request.descriptorForType.fullName) {
		LOGS -> ObservationSignal.LOG
		TRACES -> ObservationSignal.SPAN
		METRICS -> ObservationSignal.METRIC
		else -> null
	}

	fun resourcesOf(request: MessageOrBuilder, signal: ObservationSignal): List<MessageOrBuilder> =
		repeated(request, when (signal) {
			ObservationSignal.LOG -> "resource_logs"
			ObservationSignal.SPAN -> "resource_spans"
			ObservationSignal.METRIC -> "resource_metrics"
		})

	fun scopesOf(resource: MessageOrBuilder, signal: ObservationSignal): List<MessageOrBuilder> =
		repeated(resource, when (signal) {
			ObservationSignal.LOG -> "scope_logs"
			ObservationSignal.SPAN -> "scope_spans"
			ObservationSignal.METRIC -> "scope_metrics"
		})

	fun recordsOf(scope: MessageOrBuilder, signal: ObservationSignal): List<MessageOrBuilder> =
		repeated(scope, when (signal) {
			ObservationSignal.LOG -> "log_records"
			ObservationSignal.SPAN -> "spans"
			ObservationSignal.METRIC -> "metrics"
		})

	fun repeated(message: MessageOrBuilder, field: String): List<MessageOrBuilder> {
		val descriptor = message.descriptorForType.findFieldByName(field) ?: return emptyList()
		@Suppress("UNCHECKED_CAST")
		return message.getField(descriptor) as List<MessageOrBuilder>
	}

	/** 메시지 필드. 설정되지 않았으면 null. */
	fun message(message: MessageOrBuilder, field: String): MessageOrBuilder? {
		val descriptor = message.descriptorForType.findFieldByName(field) ?: return null
		return if (message.hasField(descriptor)) message.getField(descriptor) as MessageOrBuilder else null
	}

	fun string(message: MessageOrBuilder?, field: String): String =
		message?.let { m -> m.descriptorForType.findFieldByName(field)?.let { m.getField(it) as String } } ?: ""

	/** 64비트 정수 필드(부호와 무관하게 비트 그대로). 없으면 0. */
	fun long(message: MessageOrBuilder, field: String): Long =
		message.descriptorForType.findFieldByName(field)?.let { message.getField(it) as Long } ?: 0L

	fun int(message: MessageOrBuilder, field: String): Int =
		message.descriptorForType.findFieldByName(field)?.let { message.getField(it) as Int } ?: 0

	fun bytes(message: MessageOrBuilder, field: String): ByteString =
		message.descriptorForType.findFieldByName(field)?.let { message.getField(it) as ByteString } ?: ByteString.EMPTY

	fun enumNumber(message: MessageOrBuilder, field: String): Int =
		message.descriptorForType.findFieldByName(field)?.let { (message.getField(it) as EnumValueDescriptor).number } ?: 0

	/** `optional` 필드가 설정됐는가. proto3 non-optional 스칼라는 기본값이면 false 다. */
	fun has(message: MessageOrBuilder, field: String): Boolean =
		message.descriptorForType.findFieldByName(field)?.let { message.hasField(it) } ?: false

	fun double(message: MessageOrBuilder, field: String): Double =
		message.descriptorForType.findFieldByName(field)?.let { message.getField(it) as Double } ?: 0.0

	/** `attributes` 필드의 typed 속성. 순서와 중복을 그대로 둔다. */
	fun attributes(message: MessageOrBuilder?, field: String = "attributes"): List<TypedAttribute> =
		if (message == null) emptyList() else repeated(message, field).map { keyValue(it) }

	fun keyValue(keyValue: MessageOrBuilder): TypedAttribute =
		TypedAttribute(string(keyValue, "key"), message(keyValue, "value")?.let { anyValue(it) } ?: TypedValue.Empty)

	fun anyValue(value: MessageOrBuilder): TypedValue {
		val oneof = value.descriptorForType.oneofs.firstOrNull { it.name == "value" } ?: return TypedValue.Empty
		val field = value.getOneofFieldDescriptor(oneof) ?: return TypedValue.Empty
		val raw = value.getField(field)
		return when (field.name) {
			"string_value" -> TypedValue.Str(raw as String)
			"bool_value" -> TypedValue.Bool(raw as Boolean)
			"int_value" -> TypedValue.Int(raw as Long)
			"double_value" -> TypedValue.Double(raw as Double)
			"bytes_value" -> TypedValue.bytes((raw as ByteString).toByteArray())
			"array_value" -> TypedValue.Array(repeated(raw as MessageOrBuilder, "values").map { anyValue(it) })
			"kvlist_value" -> TypedValue.KvList(repeated(raw as MessageOrBuilder, "values").map { keyValue(it) })
			else -> TypedValue.Empty
		}
	}

	/** trace/span ID bytes 를 소문자 hex 로. 비었으면 null, 길이가 틀리거나 전부 0 이면 [Invalid]. */
	fun hexId(bytes: ByteString, length: Int): HexId =
		when {
			bytes.isEmpty -> HexId.Absent
			bytes.size() != length || bytes.all { it == 0.toByte() } -> HexId.Invalid
			else -> HexId.Present(bytes.joinToString("") { "%02x".format(it) })
		}

	sealed interface HexId {
		data object Absent : HexId
		data object Invalid : HexId
		data class Present(val hex: String) : HexId
	}
}

/**
 * 속성 목록에서 키 하나를 읽은 결과. 같은 키가 같은 타입·같은 값으로 여러 번 오면 하나로 본다.
 * 값이나 타입이 다르면 [Conflict] — 어느 쪽도 고르지 않는다(ADR 0020 §4).
 */
public sealed interface AttributeRead {
	public data object Absent : AttributeRead
	public data class Present(val value: TypedValue) : AttributeRead
	public data object Conflict : AttributeRead

	public companion object {
		public fun of(attributes: List<TypedAttribute>, key: String): AttributeRead {
			val values = attributes.filter { it.key == key }.map { it.value }.toSet()
			return when (values.size) {
				0 -> Absent
				1 -> Present(values.single())
				else -> Conflict
			}
		}
	}
}

/** 문자열 값 하나. 없거나 문자열이 아니거나 충돌이면 null. */
internal fun List<TypedAttribute>.singleString(key: String): String? =
	((AttributeRead.of(this, key) as? AttributeRead.Present)?.value as? TypedValue.Str)?.value
