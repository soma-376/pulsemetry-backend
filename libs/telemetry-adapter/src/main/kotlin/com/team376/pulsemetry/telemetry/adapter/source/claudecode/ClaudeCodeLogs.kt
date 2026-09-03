package com.team376.pulsemetry.telemetry.adapter.source.claudecode

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
import com.team376.pulsemetry.telemetry.adapter.model.LlmResponse
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
 * claude_code 로그 → [NormalizedLog]. 이벤트 9종.
 *
 * **프롬프트·응답 원문은 읽지 않는다.** 수집 단계에서 지워져 도착하지만 어댑터도 방어적으로
 * 드롭한다 — 애초에 담을 필드가 없다([Prompt] 는 길이와 커맨드 이름뿐이다).
 */
internal object ClaudeCodeLogs {

	fun toEvent(record: OtlpRecord, eventName: String, context: IngestContext): NormalizedLog? {
		val short = eventName.removePrefix(ClaudeCodeCommon.PREFIX)
		val attrs = record.attributes
		val resourceAttrs = record.resourceAttributes

		val session = OtlpAttributes.optString(attrs, resourceAttrs, "session.id")
			?: Envelope.UNKNOWN_SESSION
		val timestamp = OtlpTimestamp.parse(record.record, attrs)

		val event = NormalizedLog(
			envelope = Envelope(
				identity = ClaudeCodeCommon.identity(resourceAttrs, attrs, context.tenantId),
				client = ClaudeCodeCommon.client(resourceAttrs, attrs),
				timestamp = timestamp,
				sessionId = session,
				ingest = Ingest(
					adapterVersion = ClaudeCodeCommon.ADAPTER_VERSION,
					signal = context.signal,
					sourceRecordId = context.rawRecordId,
				),
			),
			turnId = OtlpAttributes.optString(attrs, "prompt.id"),
			sequence = OtlpAttributes.optInt(attrs, "event.sequence"),
		)

		when (short) {
			"api_request" -> {
				val cost = OtlpAttributes.optDouble(attrs, "cost_usd")
				event.type = LogKind.LLM_CALL
				event.payload = LlmCall(
					model = OtlpAttributes.optString(attrs, "model"),
					tokens = Tokens(
						input = OtlpAttributes.optInt(attrs, "input_tokens"),
						output = OtlpAttributes.optInt(attrs, "output_tokens"),
						cacheRead = OtlpAttributes.optInt(attrs, "cache_read_tokens"),
						cacheCreate = OtlpAttributes.optInt(attrs, "cache_creation_tokens"),
					),
					costUsd = cost,
					// claude_code 는 세 툴 중 유일하게 USD 를 직접 준다.
					costSource = if (cost != null) ValueSource.REPORTED else ValueSource.ESTIMATED,
					source = OtlpAttributes.optString(attrs, "query_source"),
					reasoningEffort = OtlpAttributes.optString(attrs, "effort"),
					durationMs = OtlpAttributes.optInt(attrs, "duration_ms"),
					ttftMs = OtlpAttributes.optInt(attrs, "ttft_ms"),
					stopReason = OtlpAttributes.optString(attrs, "stop_reason"),
					// 문서상 api_error 에만 있는 속성이다. api_request 에 생기면 자동 흡수된다.
					attempt = OtlpAttributes.optInt(attrs, "attempt"),
					requestId = OtlpAttributes.optString(attrs, "request_id"),
				)
			}

			// api_request 와 짝인 응답측. 토큰·비용은 api_request 에 있으므로 응답 고유
			// 정보만 담는다. LLM_CALL 과 분리해 '호출 수' 왜곡을 막는다.
			"assistant_response" -> {
				event.type = LogKind.LLM_RESPONSE
				event.payload = LlmResponse(
					model = OtlpAttributes.optString(attrs, "model"),
					responseLength = OtlpAttributes.optInt(attrs, "response_length"),
					source = OtlpAttributes.optString(attrs, "query_source"),
					requestId = OtlpAttributes.optString(attrs, "request_id"),
					stopReason = OtlpAttributes.optString(attrs, "stop_reason"),
				)
			}

			"api_error" -> {
				event.type = LogKind.LLM_CALL
				event.payload = LlmCall(
					model = OtlpAttributes.optString(attrs, "model"),
					// 네트워크 단절 등 HTTP 응답이 없는 오류는 status_code 가 없다.
					durationMs = OtlpAttributes.optInt(attrs, "duration_ms"),
					attempt = OtlpAttributes.optInt(attrs, "attempt"),
					requestId = OtlpAttributes.optString(attrs, "request_id"),
					errorType = OtlpAttributes.optString(attrs, "error_type", "error"),
					statusCode = OtlpAttributes.optInt(attrs, "status_code"),
				)
			}

			// 거부는 HTTP 오류가 아니라 성공 응답 스트림으로 도착한다 → 짝인 api_request 가
			// 토큰·비용을 싣고 따로 나간다. assistant_response 와 같은 이유로 LLM_RESPONSE
			// 로 두어 '호출 수' 가 2배로 왜곡되지 않게 한다.
			//
			// ⚠️ server_fallback_hop=true 는 서버가 다른 모델로 재시도해 사용자가 보지 못한
			//    홉이다. 한 턴이 hop(true) + 최종(false) 을 모두 낼 수 있으므로 거부 '건수'
			//    를 셀 때는 원본 아카이브의 server_fallback_hop 값으로 걸러야 한다.
			"api_refusal" -> {
				event.type = LogKind.LLM_RESPONSE
				event.payload = LlmResponse(
					model = OtlpAttributes.optString(attrs, "model"),
					source = OtlpAttributes.optString(attrs, "query_source"),
					requestId = OtlpAttributes.optString(attrs, "request_id"),
					// 이 이벤트의 존재 자체가 stop_reason=refusal 을 뜻한다(속성으로는 오지 않는다).
					stopReason = "refusal",
					// category 는 OTEL_LOG_TOOL_DETAILS=1 이고 has_category=true 일 때만 온다.
					refusalCategory = OtlpAttributes.optString(attrs, "category"),
				)
			}

			"tool_result", "tool_decision" -> {
				val toolName = OtlpAttributes.optString(attrs, "tool_name")
				// tool_use_id 부재는 합성으로 복구하는 알려진 케이스다(call_id_inferred 로 추적).
				val given = OtlpAttributes.optString(attrs, "tool_use_id")
				event.callId = given ?: CallId.synthesize(
					session, event.sequence, timestamp, toolName ?: "?",
				)
				event.envelope.ingest.callIdInferred = given == null
				val args = OtlpAttributes.mergeJsonAttrs(attrs, "tool_input", "tool_parameters")

				if (short == "tool_result") {
					val mcpServer = OtlpAttributes.optString(attrs, "mcp_server.name")
					event.type = LogKind.TOOL_CALL
					event.payload = ToolCall(
						toolName = toolName,
						toolKind = if (mcpServer != null) ToolKind.MCP else ToolKind.NATIVE,
						action = ClaudeCodeCommon.ACTIONS[toolName ?: ""] ?: ToolAction.OTHER,
						files = OtlpAttributes.extractFiles(args, ClaudeCodeCommon.FILE_KEYS),
						command = OtlpAttributes.extractCommand(args, ClaudeCodeCommon.COMMAND_KEYS),
						success = OtlpAttributes.optBoolean(attrs, "success"),
						errorType = OtlpAttributes.optString(attrs, "error_type"),
						durationMs = OtlpAttributes.optInt(attrs, "duration_ms"),
						mcpServer = mcpServer,
						agentId = OtlpAttributes.optString(attrs, "agent_id"),
						parentAgentId = OtlpAttributes.optString(attrs, "parent_agent_id"),
					)
				} else {
					val mapping = ClaudeCodeCommon.DECISION_SOURCES[
						OtlpAttributes.optString(attrs, "source") ?: "",
					] ?: ClaudeCodeCommon.DecisionMapping()
					event.type = LogKind.TOOL_DECISION
					event.payload = ToolDecision(
						decision = ClaudeCodeCommon.DECISION_VALUES[
							OtlpAttributes.optString(attrs, "decision") ?: "",
						] ?: mapping.decision,
						decidedBy = mapping.decidedBy,
						scope = mapping.scope,
						toolName = toolName,
					)
				}
			}

			// MCP 서버 연결 → Lifecycle. 서버·전송·상태를 승격해 "어떤 외부 서버에 붙었나"
			// (거버넌스)를 살린다.
			"mcp_server_connection" -> {
				event.type = LogKind.LIFECYCLE
				event.payload = Lifecycle(
					kind = "mcp_connection",
					attrs = promote(
						attrs,
						"server_name", "transport_type", "status", "server_scope",
						"is_plugin", "error", "duration_ms",
					),
				)
			}

			// pre/post_tokens 는 압축 전후의 '컨텍스트 크기'지 청구된 토큰이 아니다.
			// Lifecycle 에 두면 billable 과 구조적으로 섞일 수 없다 — 더하면 이중계산이 되는
			// 값이므로 이 분리가 안전장치다. 압축이 실제로 태운 토큰은 query_source="compact"
			// 인 별도 api_request 에 있다.
			"compaction" -> {
				event.type = LogKind.LIFECYCLE
				event.payload = Lifecycle(
					kind = "compaction",
					tokensBefore = OtlpAttributes.optInt(attrs, "pre_tokens"),
					tokensAfter = OtlpAttributes.optInt(attrs, "post_tokens"),
					attrs = promote(
						attrs, "trigger", "success", "duration_ms", "error", "precompute_reuse",
					),
				)
			}

			"user_prompt" -> {
				event.type = LogKind.USER_PROMPT
				event.payload = Prompt(
					length = OtlpAttributes.optInt(attrs, "prompt_length"),
					// 슬래시 커맨드가 아닌 일반 프롬프트에는 없다.
					commandName = OtlpAttributes.optString(attrs, "command_name"),
				)
			}

			else -> return null
		}

		return RecordId.finalize(event) as NormalizedLog
	}

	/**
	 * 지정한 키만 골라 문자열로 승격한다. 속성 전체를 통째로 복사하지 않는 것이 요점이다 —
	 * 화이트리스트가 원문 유출을 막는 구조적 방어다.
	 *
	 * null 과 빈 문자열은 뺀다.
	 */
	private fun promote(attrs: Map<String, Any?>, vararg keys: String): Map<String, String> {
		val out = LinkedHashMap<String, String>()
		for (key in keys) {
			val raw = attrs[key] ?: continue
			if (raw == "") continue
			out[key] = Stringify.of(raw)
		}
		return out
	}
}
