package com.team376.pulsemetry.telemetry.adapter.observation.fixture

import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.core.json.JsonFactory
import java.math.BigDecimal

/** JSON 숫자를 **원문 텍스트로** 담는다 — 2^53 을 넘는 정수와 UInt64 가 Double 을 거치지 않는다. */
data class JsonNumber(val text: String) {
	fun toBigDecimal(): BigDecimal = BigDecimal(text)
	override fun toString(): String = text
}

/**
 * fixture 용 JSON 트리 리더(테스트 전용). 객체는 삽입 순서 [LinkedHashMap], 배열은 [List], 숫자는 [JsonNumber].
 * 같은 키가 두 번 오면 실패한다 — 손으로 쓴 fixture 의 오타를 조용히 덮지 않는다.
 */
object JsonTree {
	private val factory = JsonFactory()

	fun parse(text: String): Any? = factory.createParser(text).use { parser ->
		val value = read(parser, parser.nextToken())
		require(parser.nextToken() == null) { "JSON 뒤에 남은 내용이 있다" }
		value
	}

	private fun read(parser: JsonParser, token: JsonToken?): Any? = when (token) {
		JsonToken.START_OBJECT -> {
			val out = LinkedHashMap<String, Any?>()
			while (parser.nextToken() != JsonToken.END_OBJECT) {
				val name = parser.currentName()
				require(name !in out) { "중복 키: $name" }
				out[name] = read(parser, parser.nextToken())
			}
			out
		}
		JsonToken.START_ARRAY -> buildList {
			while (true) {
				val next = parser.nextToken()
				if (next == JsonToken.END_ARRAY) break
				add(read(parser, next))
			}
		}
		JsonToken.VALUE_STRING -> parser.string
		JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT -> JsonNumber(parser.text)
		JsonToken.VALUE_TRUE -> true
		JsonToken.VALUE_FALSE -> false
		JsonToken.VALUE_NULL -> null
		else -> throw IllegalArgumentException("읽을 수 없는 토큰: $token")
	}

	/** 실패 메시지용 표기. 정확한 JSON 이 아니어도 된다. */
	fun render(value: Any?): String = when (value) {
		null -> "null"
		is String -> "\"$value\""
		is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":${render(v)}" }
		is List<*> -> value.joinToString(",", "[", "]") { render(it) }
		else -> value.toString()
	}
}
