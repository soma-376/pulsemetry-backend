package com.team376.pulsemetry.telemetry.adapter.source.claudecode

import com.team376.pulsemetry.telemetry.adapter.IngestContext
import com.team376.pulsemetry.telemetry.adapter.OtlpAttributes
import com.team376.pulsemetry.telemetry.adapter.OtlpRecord
import com.team376.pulsemetry.telemetry.adapter.OtlpTimestamp
import com.team376.pulsemetry.telemetry.adapter.RecordId
import com.team376.pulsemetry.telemetry.adapter.Stringify
import com.team376.pulsemetry.telemetry.adapter.model.Envelope
import com.team376.pulsemetry.telemetry.adapter.model.Ingest
import com.team376.pulsemetry.telemetry.adapter.model.Lifecycle
import com.team376.pulsemetry.telemetry.adapter.model.LlmCall
import com.team376.pulsemetry.telemetry.adapter.model.NormalizedSpan
import com.team376.pulsemetry.telemetry.adapter.model.SpanKind
import com.team376.pulsemetry.telemetry.adapter.model.ToolAction
import com.team376.pulsemetry.telemetry.adapter.model.ToolCall
import com.team376.pulsemetry.telemetry.adapter.model.ToolDecision
import com.team376.pulsemetry.telemetry.adapter.model.ToolKind

/**
 * claude_code 스팬 → [NormalizedSpan]. 스팬 하나가 이벤트 하나다. 이름 6종.
 *
 * 로그와 같은 무상태 규칙이다. 스팬 셋(`tool`·`tool.execution`·`tool.blocked_on_user`)을
 * 합치는 것은 조인이라 여기서 하지 않는다 — 뷰(트리 조립)의 몫이다(ADR 0017). 구조는
 * [NormalizedSpan.spanId]·[NormalizedSpan.parentId] 로, 역할은 [SpanKind] 로 보존한다.
 *
 * **토큰·비용은 담지 않는다.** 같은 LLM 호출을 로그(`api_request`)가 이미 싣고 둘은
 * `request_id` 로 조인된다 — 여기서 또 실으면 이중계산이다(ADR 0017).
 */
internal object ClaudeCodeTraces {

	fun toEvent(record: OtlpRecord, eventName: String, context: IngestContext): NormalizedSpan? {
		val short = eventName.removePrefix(ClaudeCodeCommon.PREFIX)
		val attrs = record.attributes
		val resourceAttrs = record.resourceAttributes

		val event = NormalizedSpan(
			envelope = Envelope(
				identity = ClaudeCodeCommon.identity(resourceAttrs, attrs, context.tenantId),
				client = ClaudeCodeCommon.client(resourceAttrs, attrs),
				timestamp = OtlpTimestamp.startOf(record.record),
				sessionId = OtlpAttributes.optString(attrs, resourceAttrs, "session.id")
					?: Envelope.UNKNOWN_SESSION,
				ingest = Ingest(
					adapterVersion = ClaudeCodeCommon.ADAPTER_VERSION,
					signal = context.signal,
					sourceRecordId = context.rawRecordId,
				),
			),
			traceId = record.record["traceId"] as? String,
			spanId = record.record["spanId"] as? String,
			parentId = (record.record["parentSpanId"] as? String)?.takeIf { it.isNotEmpty() },
		)
		val duration = OtlpTimestamp.durationMs(record.record)

		when (short) {
			// 턴 루트. duration·프롬프트 길이만(원문 없음). 트리 루트 마커.
			"interaction" -> {
				event.type = SpanKind.TURN
				event.payload = Lifecycle(
					kind = "turn",
					attrs = promote(
						"duration_ms" to duration,
						"prompt_length" to OtlpAttributes.optInt(attrs, "user_prompt_length"),
					),
				)
			}

			// 구조·타이밍만. 토큰·비용은 로그(api_request)에 있고 request_id 로 조인한다.
			"llm_request" -> {
				event.type = SpanKind.LLM_REQUEST
				event.payload = LlmCall(
					model = OtlpAttributes.optString(attrs, "model", "gen_ai.request.model"),
					durationMs = duration,
					ttftMs = OtlpAttributes.optInt(attrs, "ttft_ms"),
					stopReason = OtlpAttributes.optString(attrs, "stop_reason"),
					attempt = OtlpAttributes.optInt(attrs, "attempt"),
					requestId = OtlpAttributes.optString(attrs, "request_id", "client_request_id"),
				)
			}

			// 툴 호출의 '무엇'(이름·파일·명령). 성공 여부는 자식 execution 에 있다.
			"tool" -> {
				val toolName = OtlpAttributes.optString(attrs, "tool_name")
				event.type = SpanKind.TOOL
				// 로그 어댑터와 달리 **합성하지 않는다** — 없으면 null 로 둔다.
				event.callId = OtlpAttributes.optString(attrs, "tool_use_id")
				// 파일·명령은 해당 종류의 툴에만 있다.
				val file = OtlpAttributes.optString(attrs, "file_path")
				event.payload = ToolCall(
					toolName = toolName,
					toolKind = ToolKind.NATIVE,
					action = ClaudeCodeCommon.ACTIONS[toolName ?: ""] ?: ToolAction.OTHER,
					// ⚠️ 로그 경로와 달리 구분자를 통일하지 않는다. 같은 파일이 신호에 따라
					//    다른 문자열이 되는 현행 결함이고, 동작 동일성 때문에 그대로 옮긴다.
					files = if (file != null) listOf(file) else emptyList(),
					command = OtlpAttributes.optString(attrs, "full_command"),
					durationMs = duration,
				)
			}

			// 툴 호출의 '결과'(성공/실패). 부모(tool)와 parent_id 로 이어진다.
			"tool.execution" -> {
				event.type = SpanKind.TOOL_EXECUTION
				event.callId = OtlpAttributes.optString(attrs, "tool_use_id")
				event.payload = ToolCall(
					success = OtlpAttributes.optBoolean(attrs, "success"),
					errorType = OtlpAttributes.optString(attrs, "error"),
					durationMs = duration,
				)
			}

			// 승인 게이트. 결정·주체·대기시간.
			"tool.blocked_on_user" -> {
				val mapping = ClaudeCodeCommon.DECISION_SOURCES[
					OtlpAttributes.optString(attrs, "source") ?: "",
				] ?: ClaudeCodeCommon.DecisionMapping()
				event.type = SpanKind.TOOL_GATE
				event.payload = ToolDecision(
					decision = ClaudeCodeCommon.DECISION_VALUES[
						OtlpAttributes.optString(attrs, "decision") ?: "",
					] ?: mapping.decision,
					decidedBy = mapping.decidedBy,
					scope = mapping.scope,
					blockedOnUserMs = duration,
				)
			}

			// 훅 실행(베타·게이트). 이벤트·개수·소요시간을 Lifecycle attrs 로 보존한다.
			"hook" -> {
				event.type = SpanKind.HOOK
				event.payload = Lifecycle(
					kind = "hook",
					attrs = promote(
						*arrayOf(
							"hook_event", "hook_name", "num_hooks",
							"num_blocking", "num_success", "duration_ms",
						).map { it to attrs[it] }.toTypedArray(),
					),
				)
			}

			// 미지의 스팬은 무시한다.
			else -> return null
		}

		return RecordId.finalize(event)
	}

	/** null 과 빈 문자열을 뺀 뒤 문자열로 눕힌다. */
	private fun promote(vararg entries: Pair<String, Any?>): Map<String, String> {
		val out = LinkedHashMap<String, String>()
		for ((key, value) in entries) {
			if (value == null || value == "") continue
			out[key] = Stringify.of(value)
		}
		return out
	}
}
