package com.team376.pulsemetry.telemetry.adapter

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `tool_decision` ↔ `tool_call` 페어링의 golden 대조.
 *
 * **"AI 제안 수락률" KPI 가 이 조인에 걸려 있다.** 페어링은 요청 하나 안에서 돌기 때문에
 * 입력 문서 하나가 시나리오 하나다.
 */
internal class CallIdPairingGoldenTest : GoldenFixtureTestBase(
	otlpFixture = "/otlp/codex/pairing_synthetic.otlp.jsonl",
	goldenFixture = "/otlp/codex/pairing_synthetic.normalized.jsonl",
	signal = GoldenFixtures.Signal.LOGS,
) {
	private companion object {
		const val NORMAL = 0
		const val CROSS_SESSION = 1
		const val TOOL_NAME_MISMATCH = 2
		const val ORPHAN_DECISION = 3
		const val ONE_DECISION_ONE_CALL = 4
		const val TIME_INVERSION = 5
		const val NOT_INFERRED = 6
	}

	private fun callIds(documentIndex: Int) = eventsOf(documentIndex).map { it["call_id"] }

	@Test
	@DisplayName("같은 세션·같은 도구에서 결정 다음에 온 호출이 결정의 키를 물려받는다")
	fun decisionIsPairedToTheFollowingCall() {
		val (decision, call) = eventsOf(NORMAL)

		assertThat(decision["type"]).isEqualTo("tool_decision")
		assertThat(call["type"]).isEqualTo("tool_call")
		assertThat(decision["call_id"]).isEqualTo(call["call_id"])
		assertThat(decision["call_id"] as String).startsWith("syn-")
	}

	@Test
	@DisplayName("세션이 다르면 잇지 않는다")
	fun pairingDoesNotCrossSessions() {
		assertThat(callIds(CROSS_SESSION).toSet()).hasSize(2)
	}

	@Test
	@DisplayName("도구명이 다르면 잇지 않는다")
	fun pairingDoesNotCrossToolNames() {
		assertThat(callIds(TOOL_NAME_MISMATCH).toSet()).hasSize(2)
	}

	@Test
	@DisplayName("짝을 못 찾은 결정은 제 합성 키를 그대로 유지한다")
	fun orphanDecisionKeepsItsOwnKey() {
		val events = eventsOf(ORPHAN_DECISION)

		assertThat(events).hasSize(1)
		assertThat(events.single()["call_id"] as String).startsWith("syn-")
	}

	@Test
	@DisplayName("결정 하나는 호출 하나에만 소비된다")
	fun oneDecisionIsConsumedByExactlyOneCall() {
		val (decision, first, second) = eventsOf(ONE_DECISION_ONE_CALL)

		assertThat(decision["call_id"]).isEqualTo(first["call_id"])
		assertThat(decision["call_id"]).isNotEqualTo(second["call_id"])
	}

	@Test
	@DisplayName("호출이 결정보다 시각상 먼저면 잇지 않는다 — 리스트 순서가 아니라 시각으로 정렬한다")
	fun callBeforeDecisionInTimeIsNotPaired() {
		// 입력에서는 결정이 리스트 앞에 오지만 시각은 호출이 앞선다.
		assertThat(callIds(TIME_INVERSION).toSet()).hasSize(2)
	}

	@Test
	@DisplayName("call_id_inferred=false 인 이벤트는 건드리지 않는다")
	fun eventsWhoseCallIdWasNotInferredAreUntouched() {
		// claude_code 는 tool_use_id 를 주므로 합성하지 않는다 — 벤더가 준 키를 페어링이
		// 덮어쓰면 조인이 조용히 틀어진다.
		val (decision, call) = eventsOf(NOT_INFERRED)

		assertThat(decision["call_id"]).isEqualTo("toolu_pair_0001")
		assertThat(call["call_id"]).isEqualTo("toolu_pair_0002")
		listOf(decision, call).forEach {
			assertThat(it.at("envelope", "_ingest", "call_id_inferred")).isEqualTo(false)
		}
	}

	@Test
	@DisplayName("페어링이 call_id 를 바꿔도 record_id 는 흔들리지 않는다")
	fun recordIdIsNotRecomputedAfterPairing() {
		// finalize 를 페어링 **전에** 부르기 때문이다. 키가 그 레코드 자체를 가리켜야
		// 재적재가 멱등해진다.
		val (decision, call) = eventsOf(NORMAL)

		assertThat(decision["call_id"]).isEqualTo(call["call_id"])
		assertThat(decision.at("envelope", "record_id"))
			.isNotEqualTo(call.at("envelope", "record_id"))
	}

	@Test
	@DisplayName("합성 키는 syn- 와 12 hex 라는 형태를 지킨다")
	fun synthesizedKeyShapeIsPinned() {
		golden.forEach { row ->
			@Suppress("UNCHECKED_CAST")
			val event = row.event as Map<String, Any?>
			if (event.at("envelope", "_ingest", "call_id_inferred") != true) return@forEach
			assertThat(event["call_id"] as String).matches("^syn-[0-9a-f]{12}$")
		}
	}
}
