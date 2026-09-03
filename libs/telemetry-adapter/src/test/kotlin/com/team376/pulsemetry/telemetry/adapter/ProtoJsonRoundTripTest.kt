package com.team376.pulsemetry.telemetry.adapter

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * protobuf 왕복이 원본 문서 트리를 그대로 복원하는지 — **ADR 0013 의 전제 검증.**
 *
 * `_ingest.source_record_id` 는 원본 레코드 JSON 의 해시다. 이 모듈은 protobuf 를 받으므로
 * [ProtoJson] 이 그 JSON 트리를 되살려야 하는데, protobuf 는 **기본값과 같은 필드를 잃는다.**
 * 클라이언트가 `"droppedAttributesCount":0` 처럼 기본값을 명시해 보내면 복원할 수 없다.
 *
 * 이 테스트는 fixture 전 문서에 대해 그런 필드가 없음을 확인한다. 실패하면 그 문서의
 * `source_record_id` 가 구 파이프라인과 갈라진다는 뜻이다 — **fixture 를 규격에 맞게 고쳐라.**
 * `record_id`(ReplacingMergeTree 멱등 키)는 이 해시를 재료로 쓰지 않으므로 무사하다.
 */
internal class ProtoJsonRoundTripTest {

	@ParameterizedTest(name = "{0}")
	@MethodSource("fixtures")
	@DisplayName("입력 문서가 protobuf 를 지나 같은 트리로 돌아온다")
	fun roundTripsEveryFixtureDocument(resource: String, signal: GoldenFixtures.Signal) {
		val documents = GoldenFixtures.documents(resource)
		assertThat(documents).isNotEmpty()

		documents.forEachIndexed { index, original ->
			val builder = signal.newBuilder()
			OtlpJsonFixtureParser.merge(original, builder)

			val restored = ProtoJson.toTree(builder.build())

			assertThat(GoldenFixtures.canonicalize(restored))
				.`as`("$resource 의 문서 $index")
				.isEqualTo(GoldenFixtures.canonicalize(original))
		}
	}

	private companion object {
		@JvmStatic
		fun fixtures(): Stream<Arguments> = Stream.of(
			Arguments.of("/otlp/claude_code/logs_real.otlp.jsonl", GoldenFixtures.Signal.LOGS),
			Arguments.of("/otlp/claude_code/logs_synthetic.otlp.jsonl", GoldenFixtures.Signal.LOGS),
			Arguments.of("/otlp/claude_code/traces_synthetic.otlp.jsonl", GoldenFixtures.Signal.TRACES),
			Arguments.of("/otlp/claude_code/metrics_synthetic.otlp.jsonl", GoldenFixtures.Signal.METRICS),
			Arguments.of("/otlp/codex/logs_synthetic.otlp.jsonl", GoldenFixtures.Signal.LOGS),
			Arguments.of("/otlp/codex/traces_synthetic.otlp.jsonl", GoldenFixtures.Signal.TRACES),
			Arguments.of("/otlp/codex/metrics_synthetic.otlp.jsonl", GoldenFixtures.Signal.METRICS),
			Arguments.of("/otlp/codex/pairing_synthetic.otlp.jsonl", GoldenFixtures.Signal.LOGS),
			Arguments.of(
				"/otlp/codex/pairing_spans_synthetic.otlp.jsonl", GoldenFixtures.Signal.TRACES,
			),
		)
	}
}
