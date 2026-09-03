package com.team376.pulsemetry.telemetry.collector.masking

import com.team376.pulsemetry.telemetry.collector.OtlpJson
import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.KeyValue
import java.util.Base64
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * `redaction/secrets` 의 값 마스킹. 상위 `processor/redactionprocessor` (v0.157.0) 이식.
 *
 * ## 노브 셋이 정하는 것
 *
 * 배포 설정은 `allow_all_keys: true` · `redact_all_types: false` · `summary: silent` 다.
 * 상위 `processAttrs` 의 속성당 판정은 여섯 단계인데, 그 설정에서 살아남는 것은 마지막 하나뿐이다.
 *
 * ```
 * 1. shouldIgnoreKey(k)       ignored_keys · ignored_key_patterns  → 우리 설정에 없다
 * 2. shouldRedactKey(k)       allow_all_keys 가 true 라 항상 false → 키 삭제가 일어나지 않는다
 * 3. strVal = redact_all_types ? AsString() : Str()                → false 라 비문자열은 ""
 * 4. shouldAllowValue(strVal) allowed_values                       → 우리 설정에 없다
 * 5. shouldMaskKey(k)         blocked_key_patterns                 → 우리 설정에 없다
 * 6. blocked_values 적용, 값이 바뀌었으면 SetStr                     → 여기만 남는다
 * 그 후: 2 에서 예약된 키 삭제 → addMetaAttrs (summary 가 silent 라 아무것도 안 쓴다)
 * ```
 *
 * **없는 노브를 구현하지 않은 것은 의도다.** 설정에 없는 것을 미리 넣으면 위 순서를 함께 옮겨야
 * 하는데, 순서를 틀리면 조용히 다른 물건이 된다. 노브를 켜야 할 때 이 표를 보고 제자리에 넣어라.
 *
 * ## 놓치기 쉬운 비대칭 셋
 *
 * 1. **마스킹은 값 전체가 아니라 매치 구간만 바꾼다.**
 *    `"placeholder 4111111111111111"` → `"placeholder ****"` 다.
 * 2. **속성은 문자열만 본다.** `Str()` 이 비문자열에 `""` 를 주므로 결과적으로 불변이다.
 *    다만 상위는 그 `""` 도 정규식에 태우므로, **빈 문자열에 매치되는 규칙이 하나라도 생기면
 *    int 속성이 문자열 `"****"` 로 바뀐다.** 지금은 열넷 모두 매치되지 않는다
 *    (`MaskingRulesTest.noRuleMatchesEmptyString` 이 그 사실을 지킨다). 이 동작을 그대로 옮겼다.
 * 3. **로그 body 는 `redact_all_types` 를 무시한다.** `processLogBody` 의 스칼라 분기가
 *    `AsString()` 을 **무조건** 쓴다. 그래서 `redact_all_types: false` 인데도 body 의 int·bool·
 *    bytes 값이 스캔되고, 매치되면 문자열로 바뀐다. map·array 는 재귀한다.
 */
internal class SecretMasker(
	private val rules: List<Pattern> = MaskingRules.BLOCKED_VALUES,
) {

	private val replacement: String = Matcher.quoteReplacement(MaskingRules.MASK)

	/**
	 * 상위 `processStringValueForAttribute` 의 blocked_values 부분.
	 * 규칙을 **선언 순서대로** 적용하며 값을 누적 변형한다 — 앞 규칙이 바꾼 결과 위에서 다음 규칙이
	 * 돈다. `url_sanitizer` · `db_sanitizer` 는 설정에 없어 이 뒤가 비어 있다.
	 */
	fun maskString(value: String): String {
		var current = value
		for (rule in rules) {
			val matcher = rule.matcher(current)
			if (matcher.find()) {
				current = matcher.reset().replaceAll(replacement)
			}
		}
		return current
	}

	/**
	 * 속성 목록. 상위와 같이 **문자열 값만** 스캔한다(`redact_all_types: false`).
	 *
	 * 비문자열에 `""` 를 태우는 것까지 상위 그대로다 — 위 KDoc 의 비대칭 2 번이다.
	 */
	fun maskAttributes(attributes: MutableList<KeyValue.Builder>) {
		for (attribute in attributes) {
			val value = attribute.valueBuilder
			val original = if (value.hasStringValue()) value.stringValue else ""
			val masked = maskString(original)
			if (masked != original) value.stringValue = masked
		}
	}

	/**
	 * 로그 body. 상위 `processLogBody` · `redactLogBodyRecursive` 이식.
	 *
	 * map·array 는 재귀하고 스칼라는 `AsString()` 으로 펴서 스캔한다. **타입을 가리지 않는다.**
	 */
	fun maskLogBody(body: AnyValue.Builder) {
		when {
			body.hasKvlistValue() ->
				body.kvlistValueBuilder.valuesBuilderList.forEach { maskLogBody(it.valueBuilder) }

			body.hasArrayValue() ->
				body.arrayValueBuilder.valuesBuilderList.forEach { maskLogBody(it) }

			else -> {
				val original = asString(body)
				val masked = maskString(original)
				if (masked != original) body.stringValue = masked
			}
		}
	}

	/**
	 * 상위 `pcommon.Value.AsString()`. map·array 는 여기 오지 않는다 — [maskLogBody] 가 재귀한다.
	 * double 표기는 Go `encoding/json` 규칙이라 [OtlpJson.formatDoubleLikeGoJson] 을 그대로 쓴다.
	 */
	private fun asString(value: AnyValue.Builder): String = when {
		value.hasStringValue() -> value.stringValue
		value.hasBoolValue() -> value.boolValue.toString()
		value.hasIntValue() -> value.intValue.toString()
		value.hasDoubleValue() -> formatDouble(value.doubleValue)
		value.hasBytesValue() -> Base64.getEncoder().encodeToString(value.bytesValue.toByteArray())
		else -> ""
	}

	private fun formatDouble(value: Double): String = when {
		value.isNaN() -> "NaN"
		value == Double.POSITIVE_INFINITY -> "Infinity"
		value == Double.NEGATIVE_INFINITY -> "-Infinity"
		else -> OtlpJson.formatDoubleLikeGoJson(value)
	}
}
