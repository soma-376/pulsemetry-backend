package com.team376.pulsemetry.telemetry.adapter

import tools.jackson.core.JsonToken
import tools.jackson.core.json.JsonFactory

/**
 * 속성값에 문자열로 담겨 온 JSON 을 네이티브 트리로 읽는다.
 *
 * 도구 인자(`arguments`·`tool_input` 등)가 JSON 문자열로 오는 경우에만 쓴다.
 * 이식 원본의 `json.loads` 자리이고, 그것과 같은 타입을 낸다 — 객체는 [Map], 배열은 [List],
 * 정수는 [Long], 실수는 [Double] 이다.
 *
 * 수집 모듈과 같은 이유로 databind 를 쓰지 않는다 — 스트리밍 파서로 직접 순회한다.
 */
internal object JsonReader {

	private val factory = JsonFactory()

	/** 최상위가 객체인 JSON 문자열을 읽는다. 아니면 [IllegalArgumentException]. */
	fun readObject(text: String): Map<String, Any?> {
		factory.createParser(text.toByteArray(Charsets.UTF_8)).use { parser ->
			require(parser.nextToken() == JsonToken.START_OBJECT) { "최상위가 JSON 객체가 아니다" }
			@Suppress("UNCHECKED_CAST")
			return readValue(parser, JsonToken.START_OBJECT) as Map<String, Any?>
		}
	}

	private fun readValue(parser: tools.jackson.core.JsonParser, token: JsonToken): Any? =
		when (token) {
			JsonToken.START_OBJECT -> {
				val out = LinkedHashMap<String, Any?>()
				while (parser.nextToken() != JsonToken.END_OBJECT) {
					val name = parser.currentName()
					out[name] = readValue(parser, parser.nextToken())
				}
				out
			}

			JsonToken.START_ARRAY -> {
				val out = mutableListOf<Any?>()
				while (true) {
					val next = parser.nextToken()
					if (next == JsonToken.END_ARRAY) break
					out += readValue(parser, next)
				}
				out
			}

			JsonToken.VALUE_STRING -> parser.string
			JsonToken.VALUE_NUMBER_INT -> parser.longValue
			JsonToken.VALUE_NUMBER_FLOAT -> parser.doubleValue
			JsonToken.VALUE_TRUE -> true
			JsonToken.VALUE_FALSE -> false
			JsonToken.VALUE_NULL -> null
			else -> throw IllegalArgumentException("읽을 수 없는 토큰이다: $token")
		}
}
