package com.team376.pulsemetry.telemetry.adapter.observation.profile

import com.team376.pulsemetry.telemetry.adapter.observation.AttributeRead
import com.team376.pulsemetry.telemetry.adapter.observation.QualityFlag
import com.team376.pulsemetry.telemetry.adapter.observation.TypedAttribute
import com.team376.pulsemetry.telemetry.adapter.observation.TypedValue

/** 측정 정수가 허용하는 wire 표기. 프로파일이 필드마다 producer 소스로 확인한 하나를 고른다. */
public enum class NumberWire {
	/** `intValue`. */
	INT,

	/** `stringValue` 의 엄격한 10진 표기 — `0` 또는 0 으로 시작하지 않는 숫자열. 부호·공백·소수점은 허용하지 않는다. */
	DECIMAL_STRING,
}

/** 불리언이 허용하는 wire 표기. */
public enum class BoolWire {
	/** `boolValue`. */
	BOOL,

	/** `stringValue` 의 정확한 `true`/`false`. */
	STRING,
}

/**
 * 레코드 속성을 측정값으로 읽는 엄격한 규칙(ADR 0020 §4). 벤더 프로파일이 쓴다.
 *
 * - 누락은 null 이고 플래그가 없다 — 0 이나 false 로 바꾸지 않는다.
 * - 허용한 wire 타입·형식이 아니면(음수 토큰·소수·overflow·다른 타입 포함) 그 필드만 null + `invalid_measurement`.
 *   한 필드의 오류로 다른 필드를 버리지 않는다.
 * - 같은 키가 서로 다른 값·타입으로 여러 번 오면 null + `ambiguous_attribute`. 같은 값의 중복은 한 값이다.
 *
 * 읽으면서 생긴 플래그는 [flags] 에 모인다 — 관측에 붙이는 것은 호출자다.
 */
public class FieldReader(private val attributes: List<TypedAttribute>) {

	private val collected = linkedSetOf<QualityFlag>()

	/** 지금까지 읽은 필드가 남긴 플래그. */
	public val flags: Set<QualityFlag> get() = collected

	/** 키가 하나라도 있는가(값·타입과 무관). */
	public fun has(key: String): Boolean = attributes.any { it.key == key }

	/** 음이 아닌 정수. */
	public fun count(key: String, wire: NumberWire): Long? {
		val value = present(key) ?: return null
		val number = when (wire) {
			NumberWire.INT -> (value as? TypedValue.Int)?.value
			NumberWire.DECIMAL_STRING -> (value as? TypedValue.Str)?.value?.takeIf { DECIMAL.matches(it) }?.toLongOrNull()
		}
		return number?.takeIf { it >= 0 } ?: invalid()
	}

	/** 밀리초로 보고된 음이 아닌 시간을 나노초로. 나노초로 표현할 수 없으면 invalid 다. */
	public fun millisAsNanos(key: String, wire: NumberWire): Long? {
		val millis = count(key, wire) ?: return null
		return try {
			Math.multiplyExact(millis, NANOS_PER_MILLI)
		} catch (_: ArithmeticException) {
			invalid()
		}
	}

	/** 불리언. 누락을 false 로 읽지 않는다. */
	public fun bool(key: String, wire: BoolWire): Boolean? {
		val value = present(key) ?: return null
		return when (wire) {
			BoolWire.BOOL -> (value as? TypedValue.Bool)?.value
			BoolWire.STRING -> when ((value as? TypedValue.Str)?.value) {
				"true" -> true
				"false" -> false
				else -> null
			}
		} ?: invalid()
	}

	/** 문자열 값. 빈 문자열도 값이다 — 비어 있음의 의미는 프로파일이 정한다. 문자열이 아니면 null. */
	public fun text(key: String): String? = (present(key) as? TypedValue.Str)?.value

	/** 비어 있지 않은 문자열. */
	public fun nonEmptyText(key: String): String? = text(key)?.takeIf { it.isNotEmpty() }

	private fun present(key: String): TypedValue? = when (val read = AttributeRead.of(attributes, key)) {
		AttributeRead.Absent -> null
		AttributeRead.Conflict -> {
			collected += QualityFlag.AMBIGUOUS_ATTRIBUTE
			null
		}
		is AttributeRead.Present -> read.value
	}

	/** 프로파일이 필드별 범위 검사(예: HTTP 상태 0–65535)에 실패한 값을 버릴 때 쓴다 — null + `invalid_measurement`. */
	public fun <T> invalid(): T? {
		collected += QualityFlag.INVALID_MEASUREMENT
		return null
	}

	private companion object {
		val DECIMAL = Regex("0|[1-9][0-9]*")
		const val NANOS_PER_MILLI = 1_000_000L
	}
}
