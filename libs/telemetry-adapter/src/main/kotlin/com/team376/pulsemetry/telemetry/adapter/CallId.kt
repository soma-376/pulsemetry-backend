package com.team376.pulsemetry.telemetry.adapter

import com.team376.pulsemetry.telemetry.adapter.model.LogKind
import com.team376.pulsemetry.telemetry.adapter.model.Normalized
import com.team376.pulsemetry.telemetry.adapter.model.NormalizedLog
import com.team376.pulsemetry.telemetry.adapter.model.ToolCall
import com.team376.pulsemetry.telemetry.adapter.model.ToolDecision

/**
 * `call_id` 합성과 `tool_decision` ↔ `tool_call` 페어링.
 *
 * `tool_use_id` 를 주는 툴(claude_code)은 손대지 않는다. 안 주는 툴(codex)만 어댑터가
 * 합성하고([synthesize]), 여기서 세션 내 시간순으로 잇는다.
 * **"AI 제안 수락률" KPI 가 이 조인에 걸려 있다.**
 */
internal object CallId {

	/**
	 * 조인 키를 주지 않는 툴용 합성 키 — 레코드 단위 고유값.
	 *
	 * **재료 순서가 인자 순서와 다르다** — `session|toolName|sequence|timestamp` 다.
	 * `sequence` 가 없으면 `"None"` 이 재료에 들어가고 그것이 `0` 과 구별된다.
	 * `timestamp` 는 Python `repr(float)` 표기로 적는다(`1000.0`) — [Stringify] 를 통과시킨다.
	 *
	 * 이 값만으로는 결정과 실행이 이어지지 않는다. 둘을 잇는 것은 [pair] 의 사후 패스다.
	 */
	fun synthesize(session: String, sequence: Int?, timestamp: Double, toolName: String): String {
		val material = "$session|$toolName|${Stringify.of(sequence)}|${Stringify.of(timestamp)}"
		return "syn-" + SourceRecordId.sha1Hex(material).substring(0, 12)
	}

	/**
	 * 합성 키를 쓰는 툴에서 `tool_decision` ↔ `tool_call` 을 같은 키로 잇는다. **제자리에서 고친다.**
	 *
	 * 합성 키를 시각 버킷으로 만들면 승인과 실행이 몇 초 벌어질 때 서로 다른 키가 되어 조인이
	 * 조용히 깨진다. 대신 세션 내 시간순으로 "같은 도구명의 직전 미결 승인"과 짝지어 키를 물려준다.
	 *
	 * ## 스팬은 참여하지 않는다 — 결함이지만 그대로 옮긴다
	 *
	 * 후보를 [LogKind.TOOL_CALL]·[LogKind.TOOL_DECISION] 로 거르는데
	 * `SpanKind` 에는 그 값이 없다(`tool`·`tool_gate`·`tool_execution`). 그래서 codex 스팬은
	 * 합성 키를 달고도 짝지어지지 않는다. 이식은 동작 동일성이 판정 기준이라 고치지 않는다 —
	 * golden fixture 의 `codex/pairing_synthetic` 이 이 성질을 고정하고 있다.
	 *
	 * 그 결과 이 함수는 [NormalizedLog] 만 본다. 원본이 타입을 가리지 않고 `type` 값만 비교해
	 * 우연히 로그만 걸리던 것을, 여기서는 의도를 드러내 타입으로 좁혔다 — 결과는 같다.
	 */
	fun pair(events: List<Normalized>) {
		val bySession = LinkedHashMap<String, MutableList<NormalizedLog>>()
		for (event in events) {
			if (event !is NormalizedLog) continue
			if (event.callId == null) continue
			if (event.type != LogKind.TOOL_CALL && event.type != LogKind.TOOL_DECISION) continue
			bySession.getOrPut(event.envelope.sessionId) { mutableListOf() } += event
		}

		for (sessionEvents in bySession.values) {
			// tool_name -> 아직 실행과 이어지지 않은 결정의 call_id
			val pending = HashMap<String, String>()
			val ordered = sessionEvents.sortedWith(
				compareBy({ it.envelope.timestamp }, { it.sequence ?: 0 }),
			)
			for (event in ordered) {
				if (!event.envelope.ingest.callIdInferred) continue
				// 원본이 `payload.tool_name or "?"` 라 빈 문자열도 "?" 로 떨어진다.
				val key = toolName(event)?.takeIf { it.isNotEmpty() } ?: "?"
				when (event.type) {
					LogKind.TOOL_DECISION -> pending[key] = event.callId!!
					LogKind.TOOL_CALL -> pending.remove(key)?.let { event.callId = it }
					else -> Unit
				}
			}
		}
	}

	private fun toolName(event: NormalizedLog): String? = when (val payload = event.payload) {
		is ToolCall -> payload.toolName
		is ToolDecision -> payload.toolName
		else -> null
	}
}
