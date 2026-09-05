package com.team376.pulsemetry.telemetry.adapter.source.codex

import com.team376.pulsemetry.telemetry.adapter.CallId
import com.team376.pulsemetry.telemetry.adapter.IngestContext
import com.team376.pulsemetry.telemetry.adapter.OtlpAttributes
import com.team376.pulsemetry.telemetry.adapter.OtlpRecord
import com.team376.pulsemetry.telemetry.adapter.OtlpTimestamp
import com.team376.pulsemetry.telemetry.adapter.Pricing
import com.team376.pulsemetry.telemetry.adapter.RecordId
import com.team376.pulsemetry.telemetry.adapter.model.Envelope
import com.team376.pulsemetry.telemetry.adapter.model.Ingest
import com.team376.pulsemetry.telemetry.adapter.model.Lifecycle
import com.team376.pulsemetry.telemetry.adapter.model.LlmCall
import com.team376.pulsemetry.telemetry.adapter.model.LogKind
import com.team376.pulsemetry.telemetry.adapter.model.NormalizedLog
import com.team376.pulsemetry.telemetry.adapter.model.Prompt
import com.team376.pulsemetry.telemetry.adapter.model.Tokens
import com.team376.pulsemetry.telemetry.adapter.model.ToolAction
import com.team376.pulsemetry.telemetry.adapter.model.ToolCall
import com.team376.pulsemetry.telemetry.adapter.model.ToolDecision
import com.team376.pulsemetry.telemetry.adapter.model.ToolKind
import com.team376.pulsemetry.telemetry.adapter.model.ValueSource

/**
 * codex 로그 → [NormalizedLog]. 이벤트 6종.
 *
 * codex 는 조인 키(`tool_use_id`)를 주지 않아 `call_id` 를 **언제나 합성한다** —
 * `CallId.pair` 의 사후 패스가 결정과 실행을 잇는다.
 */
internal object CodexLogs {

	fun toEvent(record: OtlpRecord, eventName: String, context: IngestContext): NormalizedLog? {
		val short = eventName.removePrefix(CodexCommon.PREFIX)
		val attrs = record.attributes
		val resourceAttrs = record.resourceAttributes

		val session = OtlpAttributes.optString(
			attrs, resourceAttrs, *CodexCommon.SESSION_KEYS,
		) ?: Envelope.UNKNOWN_SESSION
		val timestamp = OtlpTimestamp.parse(record.record, attrs)

		val event = NormalizedLog(
			envelope = Envelope(
				identity = CodexCommon.identity(resourceAttrs, attrs, context.tenantId),
				client = CodexCommon.client(resourceAttrs, attrs),
				timestamp = timestamp,
				sessionId = session,
				ingest = Ingest(
					adapterVersion = CodexCommon.ADAPTER_VERSION,
					signal = context.signal,
					sourceRecordId = context.rawRecordId,
				),
			),
			// codex 는 턴 상관 ID 를 텔레메트리로 노출하지 않는다 → 세그먼트는 갭 휴리스틱 폴백.
			turnId = null,
			sequence = OtlpAttributes.optInt(attrs, "event.sequence"),
		)

		when (short) {
			"api_request" -> {
				val tokens = readTokens(attrs)
				val model = OtlpAttributes.optString(attrs, resourceAttrs, "model")
				val reported = OtlpAttributes.optDouble(attrs, "cost_usd")
				event.type = LogKind.LLM_CALL
				event.payload = LlmCall(
					model = model,
					tokens = tokens,
					costUsd = reported ?: estimate(model, tokens),
					costSource = if (reported != null) ValueSource.REPORTED else ValueSource.ESTIMATED,
					source = OtlpAttributes.optString(attrs, "originator", "session_source"),
					reasoningEffort = OtlpAttributes.optString(attrs, "model_reasoning_effort"),
					durationMs = OtlpAttributes.optInt(attrs, "duration_ms"),
					attempt = OtlpAttributes.optInt(attrs, "attempt"),
					requestId = OtlpAttributes.optString(attrs, "request_id", "client_request_id"),
					errorType = OtlpAttributes.optString(attrs, "error_type", "error"),
					statusCode = OtlpAttributes.optInt(attrs, "status_code", "status"),
				)
			}

			"sse_event" -> {
				// 두 단계로 걸러진다. 먼저 kind — 완료 이벤트가 아니면 아예 전달하지 않는다.
				if (OtlpAttributes.optString(attrs, "kind") != "response.completed") return null

				// 토큰은 response.completed 시점의 sse_event 에 실린다.
				val tokens = readTokens(attrs)
				// 다음 단계 — 토큰이 없으면 type=other, payload=null 로 남긴다.
				if (tokens.billable > 0 || tokens.totalReported != null) {
					val model = OtlpAttributes.optString(attrs, resourceAttrs, "model")
					event.type = LogKind.LLM_CALL
					event.payload = LlmCall(
						model = model,
						tokens = tokens,
						costUsd = estimate(model, tokens),
						// api_request 와 달리 보고된 비용을 보지 않는다 — 언제나 추정이다.
						costSource = ValueSource.ESTIMATED,
						source = OtlpAttributes.optString(attrs, "originator", "session_source"),
						reasoningEffort = OtlpAttributes.optString(attrs, "model_reasoning_effort"),
						durationMs = OtlpAttributes.optInt(attrs, "duration_ms"),
					)
				}
			}

			"tool_result", "tool_decision" -> {
				val toolName = OtlpAttributes.optString(attrs, "tool_name", "tool", "name")
				// codex 는 tool_use_id 를 주지 않는다 → 합성한다.
				event.callId = CallId.synthesize(
					session, event.sequence, timestamp, toolName ?: "?",
				)
				event.envelope.ingest.callIdInferred = true

				if (short == "tool_result") {
					val args = OtlpAttributes.mergeJsonAttrs(
						attrs, "tool_input", "tool_parameters", "arguments", "input",
					)
					event.type = LogKind.TOOL_CALL
					event.payload = ToolCall(
						toolName = toolName,
						// codex 는 MCP 여부를 구분해 주지 않는다 → 언제나 native 다.
						toolKind = ToolKind.NATIVE,
						action = CodexCommon.ACTIONS[(toolName ?: "").lowercase()]
							?: ToolAction.OTHER,
						files = OtlpAttributes.extractFiles(args, CodexCommon.FILE_KEYS),
						command = OtlpAttributes.extractCommand(args, CodexCommon.COMMAND_KEYS),
						success = OtlpAttributes.optBoolean(attrs, "success"),
						errorType = OtlpAttributes.optString(attrs, "error_type", "error"),
						durationMs = OtlpAttributes.optInt(attrs, "duration_ms"),
					)
				} else {
					val resolved = CodexCommon.resolveDecision(attrs)
					event.type = LogKind.TOOL_DECISION
					event.payload = ToolDecision(
						decision = resolved.decision,
						decidedBy = resolved.decidedBy,
						scope = resolved.scope,
						toolName = toolName,
					)
				}
			}

			"user_prompt" -> {
				event.type = LogKind.USER_PROMPT
				// claude_code 와 달리 command_name 을 읽지 않는다.
				event.payload = Prompt(length = OtlpAttributes.optInt(attrs, "prompt_length", "length"))
			}

			"conversation_starts" -> {
				event.type = LogKind.LIFECYCLE
				event.payload = Lifecycle(kind = "session_start")
			}

			else -> return null
		}

		return RecordId.finalize(event)
	}

	/**
	 * 토큰 다섯 칸을 읽는다. api_request 와 sse_event 가 같은 키 목록을 쓴다.
	 *
	 * **`cacheCreate` 는 읽지 않는다** — codex 가 캐시 생성 토큰을 구분하지 않아 언제나
	 * null 이다. 그래서 추정 비용에서도 0 으로 들어간다.
	 */
	private fun readTokens(attrs: Map<String, Any?>): Tokens = Tokens(
		input = OtlpAttributes.optInt(attrs, "input_token_count", "input_tokens", "prompt_tokens"),
		output = OtlpAttributes.optInt(
			attrs, "output_token_count", "output_tokens", "completion_tokens",
		),
		cacheRead = OtlpAttributes.optInt(
			attrs, "cached_token_count", "cached_input_tokens", "cache_read_tokens", "cached_tokens",
		),
		reasoning = OtlpAttributes.optInt(
			attrs, "reasoning_token_count", "reasoning_output_tokens", "reasoning_tokens",
		),
		totalReported = OtlpAttributes.optInt(attrs, "total_tokens"),
	)

	/**
	 * 단가표 추정. **과금 대상 네 칸만 넘긴다** — `reasoning` 을 더하면 이중계산이다.
	 *
	 * 과금 대상이 하나도 없으면 추정하지 않는다(0 이 아니라 null).
	 */
	private fun estimate(model: String?, tokens: Tokens): Double? =
		if (tokens.billable > 0) {
			Pricing.estimate(
				model,
				tokens.input ?: 0,
				tokens.output ?: 0,
				tokens.cacheRead ?: 0,
				tokens.cacheCreate ?: 0,
			)
		} else {
			null
		}
}
