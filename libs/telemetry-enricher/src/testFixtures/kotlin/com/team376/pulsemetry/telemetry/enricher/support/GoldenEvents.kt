package com.team376.pulsemetry.telemetry.enricher.support

import com.team376.pulsemetry.telemetry.adapter.model.Normalized
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.core.json.JsonFactory

/**
 * golden fixture 를 읽어 실측 이벤트를 만드는 공통 하네스. **테스트 전용이다.**
 *
 * 보강 단계와 적재 단계가 같은 이벤트로 돈다 — 적재 모듈은
 * `testImplementation(testFixtures(project(":libs:telemetry-enricher")))` 로 가져간다
 * (ADR 0008 규칙 6).
 *
 * 출처와 한계는 `src/testFixtures/resources/otlp/README.md` 가 담는다.
 */
public object GoldenEvents {

	private val factory = JsonFactory()

	/** 이 모듈이 들고 있는 fixture 전부. 세 신호와 두 제품을 덮는다. */
	public val FIXTURES: List<String> = listOf(
		"/otlp/claude_code/logs_real.normalized.jsonl",
		"/otlp/claude_code/logs_synthetic.normalized.jsonl",
		"/otlp/claude_code/traces_synthetic.normalized.jsonl",
		"/otlp/claude_code/metrics_synthetic.normalized.jsonl",
		"/otlp/codex/logs_synthetic.normalized.jsonl",
		"/otlp/codex/traces_synthetic.normalized.jsonl",
		"/otlp/codex/metrics_synthetic.normalized.jsonl",
	)

	/** 로그 9종을 다 담은 fixture. 한 건만 필요할 때 여기서 고른다. */
	public const val CLAUDE_CODE_LOGS: String = "/otlp/claude_code/logs_synthetic.normalized.jsonl"

	/** fixture 의 `event` 트리들. 대조용 원본이다. */
	@Suppress("UNCHECKED_CAST")
	public fun trees(resource: String): List<Map<String, Any?>> = lines(resource).map { line ->
		(readTree(line) as Map<String, Any?>)["event"] as Map<String, Any?>
	}

	/** fixture 를 이벤트로 되돌린다. */
	public fun events(resource: String): List<Normalized> = trees(resource).map(NormalizedJsonReader::read)

	/** 모든 fixture 의 이벤트. */
	public fun all(): List<Normalized> = FIXTURES.flatMap(::events)

	/**
	 * 값 동등성 비교를 위해 트리를 눕힌다.
	 *
	 * 정수는 [Long], 실수는 [Double] 로 맞춘다. **정수와 실수를 섞지는 않는다** —
	 * `1` 과 `1.0` 의 구분은 여전히 판정 대상이다. 어댑터의 `GoldenFixtures` 와 같은 규칙이다.
	 */
	public fun canonicalize(value: Any?): Any? = when (value) {
		is Map<*, *> -> value.entries.associate { (key, item) -> key as String to canonicalize(item) }
		is Iterable<*> -> value.map { canonicalize(it) }
		is Double, is Float -> (value as Number).toDouble()
		is Number -> value.toLong()
		else -> value
	}

	/** JSON 한 덩이를 트리로 읽는다. ClickHouse 가 돌려준 `raw_json` 을 대조할 때도 쓴다. */
	public fun parse(json: String): Any? = readTree(json)

	public fun lines(resource: String): List<String> =
		GoldenEvents::class.java.getResourceAsStream(resource)
			?.bufferedReader()?.readLines()?.filter { it.isNotBlank() }
			?: error("$resource 를 찾지 못했다")

	private fun readTree(text: String): Any? =
		factory.createParser(text.toByteArray(Charsets.UTF_8)).use { parser ->
			parser.nextToken()
			read(parser)
		}

	private fun read(parser: JsonParser): Any? = when (parser.currentToken()) {
		JsonToken.START_OBJECT -> {
			val out = LinkedHashMap<String, Any?>()
			while (parser.nextToken() != JsonToken.END_OBJECT) {
				val name = parser.currentName()
				parser.nextToken()
				out[name] = read(parser)
			}
			out
		}

		JsonToken.START_ARRAY -> {
			val out = mutableListOf<Any?>()
			while (parser.nextToken() != JsonToken.END_ARRAY) out += read(parser)
			out
		}

		JsonToken.VALUE_STRING -> parser.string
		JsonToken.VALUE_NUMBER_INT -> parser.longValue
		JsonToken.VALUE_NUMBER_FLOAT -> parser.doubleValue
		JsonToken.VALUE_TRUE -> true
		JsonToken.VALUE_FALSE -> false
		JsonToken.VALUE_NULL -> null
		else -> error("읽을 수 없는 토큰이다: ${parser.currentToken()}")
	}
}
