package com.team376.pulsemetry.telemetry.adapter

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 스팬이 페어링에 참여하지 않는다는 현행 동작의 고정.
 *
 * `CallId.pair` 가 `LogKind.TOOL_CALL`/`TOOL_DECISION` 로 후보를 거르는데 `SpanKind` 에는
 * 그 값이 없다(`tool`·`tool_gate`·`tool_execution`). 그래서 codex 스팬은 합성 키를 달고도
 * 짝지어지지 않는다. 결함이지만 이식은 동작 동일성이 판정 기준이라 그대로 옮긴다 —
 * 같은 조건의 로그 쌍이 이어지는 것은 [CallIdPairingGoldenTest] 가 보여 준다.
 */
internal class CodexSpanPairingGoldenTest : GoldenFixtureTestBase(
	otlpFixture = "/otlp/codex/pairing_spans_synthetic.otlp.jsonl",
	goldenFixture = "/otlp/codex/pairing_spans_synthetic.normalized.jsonl",
	signal = GoldenFixtures.Signal.TRACES,
	expectedProduct = "codex",
	expectedAdapterVersion = 2,
) {
	@Test
	@DisplayName("같은 세션·같은 도구·시간순인데도 스팬 쌍은 이어지지 않는다")
	fun spansDoNotParticipateInPairing() {
		val (gate, execution) = eventsOf(0)

		assertThat(gate["type"]).isEqualTo("tool_gate")
		assertThat(execution["type"]).isEqualTo("tool_execution")
		assertThat(gate.at("envelope", "session_id"))
			.isEqualTo(execution.at("envelope", "session_id"))
		assertThat(gate.at("payload", "tool_name")).isEqualTo(execution.at("payload", "tool_name"))
		assertThat(gate.at("envelope", "timestamp") as Double)
			.isLessThan(execution.at("envelope", "timestamp") as Double)

		assertThat(gate["call_id"]).isNotEqualTo(execution["call_id"])
		listOf(gate, execution).forEach {
			assertThat(it.at("envelope", "_ingest", "call_id_inferred")).isEqualTo(true)
			assertThat(it["call_id"] as String).startsWith("syn-")
		}
	}
}
