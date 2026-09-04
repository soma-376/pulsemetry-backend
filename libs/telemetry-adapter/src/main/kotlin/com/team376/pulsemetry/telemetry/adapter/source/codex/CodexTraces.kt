package com.team376.pulsemetry.telemetry.adapter.source.codex

import com.team376.pulsemetry.telemetry.adapter.CallId
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
 * codex 스팬 → [NormalizedSpan]. 이름 4종.
 *
 * codex 는 이 이름들을 구조화된 OTel 이벤트로 문서화한다. traces 엔드포인트에서 같은 이름을
 * 받아 두면 그 이벤트를 스팬으로 올리는 collector 에도 그대로 쓸 수 있다.
 *
 * ⚠️ 도구 스팬도 `call_id` 를 합성하지만 **페어링에 참여하지 않는다** — `CallId.pair` 가
 * `LogKind` 로 후보를 거르기 때문이다. 현행 결함이고 golden fixture 가 고정하고 있다.
 */
internal object CodexTraces {

	fun toEvent(record: OtlpRecord, eventName: String, context: IngestContext): NormalizedSpan? {
		val short = eventName.removePrefix(CodexCommon.PREFIX)
		val attrs = record.attributes
		val resourceAttrs = record.resourceAttributes

		val session = OtlpAttributes.optString(
			attrs, resourceAttrs, *CodexCommon.SESSION_KEYS,
		) ?: Envelope.UNKNOWN_SESSION
		val startedAt = OtlpTimestamp.startOf(record.record)

		val event = NormalizedSpan(
			envelope = Envelope(
				identity = CodexCommon.identity(resourceAttrs, attrs, context.tenantId),
				client = CodexCommon.client(resourceAttrs, attrs),
				timestamp = startedAt,
				sessionId = session,
				ingest = Ingest(
					adapterVersion = CodexCommon.ADAPTER_VERSION,
					signal = context.signal,
					sourceRecordId = context.rawRecordId,
				),
			),
			traceId = record.record["traceId"] as? String,
			spanId = record.record["spanId"] as? String,
			parentId = (record.record["parentSpanId"] as? String)?.takeIf { it.isNotEmpty() },
		)
		// 시작·끝이 없으면 duration_ms 속성으로 물러난다 — claude_code 쪽과 다른 점이다.
		val duration = OtlpTimestamp.durationMs(record.record)
			?: OtlpAttributes.optInt(attrs, "duration_ms")

		when (short) {
			"conversation_starts" -> {
				event.type = SpanKind.TURN
				event.payload = Lifecycle(
					kind = "session_start",
					attrs = promote(
						"duration_ms" to duration,
						"reasoning_effort" to
							OtlpAttributes.optString(attrs, "model_reasoning_effort"),
						// ⚠️ 아래 둘은 **언제나 null 이다.** 입력에 실려 있어도 읽지 않는다 —
						//    현행 결함이고 golden fixture 의 codex/traces_synthetic 이 고정한다.
						"approval_policy" to null,
						"sandbox_policy" to null,
					),
				)
			}

			"api_request" -> {
				event.type = SpanKind.LLM_REQUEST
				// 토큰·비용은 담지 않는다 — 로그가 이미 싣고 있어 이중계산이 된다.
				event.payload = LlmCall(
					model = OtlpAttributes.optString(attrs, resourceAttrs, "model"),
					durationMs = duration,
					attempt = OtlpAttributes.optInt(attrs, "attempt"),
					requestId = OtlpAttributes.optString(attrs, "request_id", "client_request_id"),
					errorType = OtlpAttributes.optString(attrs, "error_type", "error"),
					statusCode = OtlpAttributes.optInt(attrs, "status_code", "status"),
				)
			}

			"tool_result" -> {
				val toolName = OtlpAttributes.optString(attrs, "tool_name", "tool", "name")
				val args = OtlpAttributes.mergeJsonAttrs(
					attrs, "tool_input", "tool_parameters", "arguments", "input",
				)
				event.type = SpanKind.TOOL_EXECUTION
				// 스팬에는 sequence 가 없다 → 합성 키 재료의 그 자리는 null 이다.
				event.callId = CallId.synthesize(session, null, startedAt, toolName ?: "?")
				event.envelope.ingest.callIdInferred = true
				event.payload = ToolCall(
					toolName = toolName,
					toolKind = ToolKind.NATIVE,
					action = CodexCommon.ACTIONS[(toolName ?: "").lowercase()] ?: ToolAction.OTHER,
					files = OtlpAttributes.extractFiles(args, CodexCommon.FILE_KEYS),
					command = OtlpAttributes.extractCommand(args, CodexCommon.COMMAND_KEYS),
					success = OtlpAttributes.optBoolean(attrs, "success"),
					errorType = OtlpAttributes.optString(attrs, "error_type", "error"),
					durationMs = duration,
				)
			}

			"tool_decision" -> {
				val toolName = OtlpAttributes.optString(attrs, "tool_name", "tool", "name")
				val resolved = CodexCommon.resolveDecision(attrs)
				event.type = SpanKind.TOOL_GATE
				event.callId = CallId.synthesize(session, null, startedAt, toolName ?: "?")
				event.envelope.ingest.callIdInferred = true
				event.payload = ToolDecision(
					decision = resolved.decision,
					decidedBy = resolved.decidedBy,
					scope = resolved.scope,
					blockedOnUserMs = duration,
					toolName = toolName,
				)
			}

			// 그 밖의 codex 스팬은 전달하지 않는다.
			else -> return null
		}

		return RecordId.finalize(event)
	}

	/** null 을 뺀 뒤 문자열로 눕힌다. */
	private fun promote(vararg entries: Pair<String, Any?>): Map<String, String> {
		val out = LinkedHashMap<String, String>()
		for ((key, value) in entries) {
			if (value == null) continue
			out[key] = Stringify.of(value)
		}
		return out
	}
}
