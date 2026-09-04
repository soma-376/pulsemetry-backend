package com.team376.pulsemetry.telemetry.adapter

import com.google.protobuf.Message
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import tools.jackson.core.JsonToken
import tools.jackson.core.json.JsonFactory

/**
 * golden fixture 를 읽어 대조 가능한 형태로 만드는 공통 하네스.
 *
 * 입력은 OTLP/JSON 한 줄이 문서 하나다. 이 모듈의 진입점은 protobuf 를 받으므로 줄마다
 * 요청 메시지로 파싱해 [Normalizer] 에 넣는다 — 조립 앱에서 수집 모듈이 하는 일과 같다.
 *
 * 기대출력과의 대조는 **JSON 값 동등성**이다. 숫자는 파서가 만드는 자바 타입이 갈리므로
 * ([Int] vs [Long] vs [Double]) [canonicalize] 로 눕힌 뒤 비교한다.
 */
internal object GoldenFixtures {

	private val factory = JsonFactory()

	/** 한 이벤트 행. golden 의 한 줄과 같은 모양이다. */
	data class Row(val documentIndex: Int, val eventIndex: Int, val event: Any?)

	/** 입력 fixture 를 문서별 요청 메시지로 읽는다. */
	fun requests(resource: String, signal: Signal): List<Message> =
		documents(resource).map { document ->
			val builder = signal.newBuilder()
			OtlpJsonFixtureParser.merge(document, builder)
			builder.build()
		}

	/** 입력 fixture 를 JSON 트리 리스트로 읽는다. protobuf 왕복 검증이 원본 트리를 쓴다. */
	@Suppress("UNCHECKED_CAST")
	fun documents(resource: String): List<Map<String, Any?>> =
		lines(resource).map { readTree(it) as Map<String, Any?> }

	/** 기대출력 fixture 를 행 리스트로 읽는다. */
	fun golden(resource: String): List<Row> = lines(resource).map { line ->
		@Suppress("UNCHECKED_CAST")
		val row = readTree(line) as Map<String, Any?>
		Row(
			documentIndex = (row["document_index"] as Number).toInt(),
			eventIndex = (row["event_index"] as Number).toInt(),
			event = canonicalize(row["event"]),
		)
	}

	/** 요청들을 정규화해 golden 과 같은 모양으로 편다. */
	fun normalize(requests: List<Message>): List<Row> {
		val rows = mutableListOf<Row>()
		requests.forEachIndexed { documentIndex, request ->
			Normalizer.normalize(request).forEachIndexed { eventIndex, event ->
				rows += Row(documentIndex, eventIndex, canonicalize(NormalizedJson.toTree(event)))
			}
		}
		return rows
	}

	/** 한 입력 문서가 낸 이벤트만 고른다. 문서 단위 규칙(페어링 등)을 볼 때 쓴다. */
	@Suppress("UNCHECKED_CAST")
	fun eventsOf(rows: List<Row>, documentIndex: Int): List<Map<String, Any?>> =
		rows.filter { it.documentIndex == documentIndex }.map { it.event as Map<String, Any?> }

	/**
	 * 값 동등성 비교를 위해 트리를 눕힌다.
	 *
	 * 정수는 전부 [Long], 실수는 전부 [Double] 로 만든다. 그러지 않으면 파서가 `12345` 를
	 * [Int] 로, 우리 코드가 [Long] 으로 만들어 같은 값이 다르다고 나온다. **정수와 실수를
	 * 섞지는 않는다** — `1` 과 `1.0` 은 여전히 다르다. 그 구분이 이식의 판정 대상이다.
	 */
	fun canonicalize(value: Any?): Any? = when (value) {
		is Map<*, *> -> value.entries.associate { (key, item) -> key as String to canonicalize(item) }
		is Iterable<*> -> value.map { canonicalize(it) }
		is Double, is Float -> (value as Number).toDouble()
		is Number -> value.toLong()
		else -> value
	}

	/**
	 * golden 한 줄에서 `"event":` 뒤의 원문을 그대로 잘라 낸다.
	 *
	 * 행 모양이 `{"document_index":N,"event_index":M,"event":{…}}` 로 고정돼 있으므로 `"event":`
	 * 뒤부터 마지막 `}` 앞까지가 이벤트 JSON 이다. 파싱해 다시 적지 않는다 — 표기 자체가 판정 대상이다.
	 */
	fun eventJsonOf(line: String): String {
		val marker = "\"event\":"
		val start = line.indexOf(marker)
		require(start >= 0 && line.endsWith("}")) { "golden 행 모양이 아니다: $line" }
		return line.substring(start + marker.length, line.length - 1)
	}

	fun lines(resource: String): List<String> =
		GoldenFixtures::class.java.getResourceAsStream(resource)
			?.bufferedReader()?.readLines()?.filter { it.isNotBlank() }
			?: error("$resource 를 찾지 못했다")

	private fun readTree(text: String): Any? =
		factory.createParser(text.toByteArray(Charsets.UTF_8)).use { parser ->
			parser.nextToken()
			read(parser)
		}

	private fun read(parser: tools.jackson.core.JsonParser): Any? =
		when (parser.currentToken()) {
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

	/** fixture 가 어느 요청 타입으로 들어가는지. */
	enum class Signal {
		LOGS, TRACES, METRICS;

		fun newBuilder(): Message.Builder = when (this) {
			LOGS -> ExportLogsServiceRequest.newBuilder()
			TRACES -> ExportTraceServiceRequest.newBuilder()
			METRICS -> ExportMetricsServiceRequest.newBuilder()
		}
	}
}
