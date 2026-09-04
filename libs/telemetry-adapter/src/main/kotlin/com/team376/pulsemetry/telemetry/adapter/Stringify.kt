package com.team376.pulsemetry.telemetry.adapter

/**
 * 값을 문자열로 눕힌다 — 이식 원본의 Python `str()` 과 같은 표기.
 *
 * `Lifecycle.attrs` 와 `MetricPoint.attrs` 가 값을 전부 String 으로 담기 때문에, 이 표기가
 * 그대로 golden fixture 의 값이 된다.
 *
 * **Boolean 이 `"True"`/`"False"` 다.** Kotlin 기본값(`"true"`)을 쓰면 그 속성을 가진 이벤트가
 * 전부 어긋난다. `null` 이 `"None"` 인 것도 같은 이유로 중요하다 — [CanonicalJson] 의
 * 소문자 `true`/`null` 은 JSON 문법이고 이쪽은 값 자체다. 둘을 섞지 마라.
 */
internal object Stringify {

	fun of(value: Any?): String = when (value) {
		null -> "None"
		is Boolean -> if (value) "True" else "False"
		is Double -> CanonicalJson.formatDouble(value)
		is Float -> CanonicalJson.formatDouble(value.toDouble())
		is String -> value
		// 속성값은 `AnyValue` 에서 오므로 컨테이너가 실제로 나타나지는 않는다
		// (`OtlpAttributes.value` 가 스칼라 넷과 null 만 낸다). 도달하지 않는 분기다.
		is Map<*, *>, is Iterable<*> -> CompactJson.encode(value)
		else -> value.toString()
	}

	/** `MetricPoint.attrs` 전용 — 메트릭 속성만 컨테이너를 compact JSON 으로 눕히고, 나머지는 [of] 와 같다. */
	fun attrs(attrs: Map<String, Any?>): Map<String, String> =
		attrs.mapValues { (_, value) -> of(value) }
}

/**
 * Python `json.dumps(value, ensure_ascii=False, separators=(",", ":"))` — 공백 없는 표기.
 *
 * [CanonicalJson] 과 달리 키를 정렬하지 않고 구분자에 공백이 없다. 해시가 아니라 값 승격에
 * 쓰이므로 규칙이 다르다.
 */
internal object CompactJson {

	fun encode(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

	private fun write(out: StringBuilder, value: Any?) {
		when (value) {
			null -> out.append("null")
			is Boolean -> out.append(if (value) "true" else "false")
			is String -> out.append(CanonicalJson.encode(value))
			is Double -> out.append(CanonicalJson.formatDouble(value))
			is Number -> out.append(value.toString())
			is Map<*, *> -> {
				out.append('{')
				value.entries.forEachIndexed { index, (key, item) ->
					if (index > 0) out.append(',')
					out.append(CanonicalJson.encode(key as String)).append(':')
					write(out, item)
				}
				out.append('}')
			}

			is Iterable<*> -> {
				out.append('[')
				value.forEachIndexed { index, item ->
					if (index > 0) out.append(',')
					write(out, item)
				}
				out.append(']')
			}

			else -> throw IllegalArgumentException("JSON 으로 옮길 수 없는 값이다: ${value::class}")
		}
	}
}
