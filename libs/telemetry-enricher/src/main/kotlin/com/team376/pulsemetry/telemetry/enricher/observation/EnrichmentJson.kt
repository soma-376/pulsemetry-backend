package com.team376.pulsemetry.telemetry.enricher.observation

/**
 * `enrichment_json` 의 표기 — 키를 정렬하고 구분자 공백이 없다(`json.dumps(..., sort_keys=True, separators=(",", ":"),
 * ensure_ascii=False)`). 구 적재 경로가 저장하던 값과 바이트가 같아야 하므로 이스케이프도 같다.
 *
 * 구 경로의 인코더는 적재 모듈의 `internal` 이라 쓸 수 없다(ADR 0014 — 단계 간 간선은 데이터 타입만). 지금의 산출물은
 * 문자열 목록과 빈 객체뿐이라 정수·문자열·불리언·null·객체·배열만 옮긴다. **실수는 거부한다** — 구 표기(Python
 * `repr(float)`)를 옮기지 않은 채 실수를 받으면 저장 값이 조용히 갈린다. 실수를 내는 provider 가 붙을 때 그 규칙을
 * 함께 옮긴다.
 */
internal object EnrichmentJson {

	fun sorted(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

	private fun write(out: StringBuilder, value: Any?) {
		when (value) {
			null -> out.append("null")
			is Boolean -> out.append(if (value) "true" else "false")
			is String -> writeString(out, value)
			is Int, is Long, is Short, is Byte -> out.append(value.toString())
			is Map<*, *> -> {
				out.append('{')
				value.keys.map { it as String }.sorted().forEachIndexed { index, key ->
					if (index > 0) out.append(',')
					writeString(out, key)
					out.append(':')
					write(out, value[key])
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
			else -> throw IllegalArgumentException("enrichment_json 으로 옮길 수 없는 값이다: ${value::class}")
		}
	}

	/** Python `py_encode_basestring` 과 같다 — 따옴표·역슬래시·제어문자만 이스케이프하고 나머지는 원문이다. */
	private fun writeString(out: StringBuilder, value: String) {
		out.append('"')
		for (char in value) {
			when (char) {
				'"' -> out.append("\\\"")
				'\\' -> out.append("\\\\")
				'\n' -> out.append("\\n")
				'\r' -> out.append("\\r")
				'\t' -> out.append("\\t")
				'\b' -> out.append("\\b")
				'\u000C' -> out.append("\\f")
				else -> if (char < ' ') out.append("\\u").append("%04x".format(char.code)) else out.append(char)
			}
		}
		out.append('"')
	}
}
