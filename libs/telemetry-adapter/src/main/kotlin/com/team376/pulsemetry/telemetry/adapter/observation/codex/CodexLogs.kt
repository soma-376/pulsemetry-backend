package com.team376.pulsemetry.telemetry.adapter.observation.codex

import com.team376.pulsemetry.telemetry.adapter.observation.Decision
import com.team376.pulsemetry.telemetry.adapter.observation.DecisionScope
import com.team376.pulsemetry.telemetry.adapter.observation.DecisionSource
import com.team376.pulsemetry.telemetry.adapter.observation.ErrorType
import com.team376.pulsemetry.telemetry.adapter.observation.EventObservation
import com.team376.pulsemetry.telemetry.adapter.observation.EventType
import com.team376.pulsemetry.telemetry.adapter.observation.MappingStatus
import com.team376.pulsemetry.telemetry.adapter.observation.MetadataAllowlist
import com.team376.pulsemetry.telemetry.adapter.observation.Operation
import com.team376.pulsemetry.telemetry.adapter.observation.QualityFlag
import com.team376.pulsemetry.telemetry.adapter.observation.ReasoningEffort
import com.team376.pulsemetry.telemetry.adapter.observation.ToolAction
import com.team376.pulsemetry.telemetry.adapter.observation.ToolOrigin
import com.team376.pulsemetry.telemetry.adapter.observation.TtftScope
import com.team376.pulsemetry.telemetry.adapter.observation.UsageRole
import com.team376.pulsemetry.telemetry.adapter.observation.UsageScope
import com.team376.pulsemetry.telemetry.adapter.observation.profile.BoolWire
import com.team376.pulsemetry.telemetry.adapter.observation.profile.FieldReader
import com.team376.pulsemetry.telemetry.adapter.observation.profile.LogProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.LogRecordView
import com.team376.pulsemetry.telemetry.adapter.observation.profile.MappedEvent
import com.team376.pulsemetry.telemetry.adapter.observation.profile.NumberWire
import com.team376.pulsemetry.telemetry.adapter.observation.withFlags

/**
 * Codex 로그 매핑(ADR 0020 부록 A.2·A.3). 의미 이름은 속성 `event.name` 이다 — 최상위 `eventName` 은 producer 의
 * 소스 위치라 의미 이름으로 쓰지 않고 metadata 에 남긴다.
 *
 * 필드의 wire 타입은 producer 가 기록하는 그대로만 받는다(근거 문서 7): `%` 로 기록한 토큰·`duration_ms` 는 10진
 * 문자열, 나머지 정수는 `intValue`. 다른 표기는 그 필드만 null + `invalid_measurement` 다.
 *
 * 세션은 `conversation.id` 다. 다른 신호나 같은 세션의 다른 로그(`conversation_starts` 의 provider 등)의 값을
 * 옮겨 채우지 않는다.
 */
internal object CodexLogs : LogProfile {

	override val allowlist: MetadataAllowlist = MetadataAllowlist(
		version = "codex-logs-v1",
		resource = setOf("service.name", "service.version", "env", "telemetry.sdk.name", "telemetry.sdk.language", "telemetry.sdk.version"),
		record = setOf(
			// 공통 세션 속성 — 계정·이메일·사용자 지정 에이전트 이름은 뺀다.
			"event.name", "event.timestamp", "conversation.id", "app.version", "auth_mode", "originator", "terminal.type", "model", "slug",
			// SSE
			"event.kind", "duration_ms", "ttft_ms", "service_tier", "model_reasoning_effort",
			INPUT, OUTPUT, CACHED, CACHE_WRITE, REASONING, TOTAL,
			// 요청·연결·인증
			"http.response.status_code", "attempt", "success", "endpoint",
			"auth.header_attached", "auth.header_name", "auth.retry_after_unauthorized", "auth.recovery_mode", "auth.recovery_phase",
			"auth.connection_reused", "auth.error_code", "auth.mode", "auth.step", "auth.outcome", "auth.state_changed",
			"auth.env_openai_api_key_present", "auth.env_codex_api_key_present", "auth.env_codex_api_key_enabled",
			"auth.env_provider_key_present", "auth.env_refresh_token_url_override_present",
			// 도구·결정
			"tool_name", "tool_namespace", "call_id", "tool_result_seq", "output_truncated", "mcp_server", "decision", "source",
			"outcome", "initial_duration_ms", "escalated_duration_ms",
			// 그 밖
			"prompt_length", "provider_name", "reasoning_effort", "reasoning_summary", "context_window", "auto_compact_token_limit",
			"approval_policy", "sandbox_policy", "startup.phase", "startup.status",
			"communication_id", "kind", "state", "sender_thread_id", "receiver_thread_id",
		),
	)

	override val keepsEventName: Boolean = true

	override fun semanticName(view: LogRecordView): String? = FieldReader(view.attributes).nonEmptyText(EVENT_NAME)

	override fun map(name: String, view: LogRecordView, base: EventObservation): MappedEvent? {
		val fields = FieldReader(view.attributes)
		val mapped = when (name) {
			"codex.sse_event" -> sse(fields, common(fields, base))
			"codex.api_request" -> apiRequest(fields, view, common(fields, base))
			"codex.websocket_connect" -> websocketConnect(fields, common(fields, base))
			"codex.websocket_request" -> websocketRequest(fields, common(fields, base))
			"codex.tool_result" -> toolResult(fields, common(fields, base))
			"codex.tool_decision" -> toolDecision(fields, common(fields, base))
			"codex.sandbox_outcome" -> common(fields, base).copy(
				eventType = EventType.SANDBOX_OUTCOME,
				callId = callId(fields),
				callIdNamespace = callId(fields)?.let { CALL_NAMESPACE },
				toolName = fields.nonEmptyText("tool_name"),
				// 초기 실행 시간. 상승(escalated) 실행 시간과 outcome 은 metadata 에 남는다.
				durationNs = fields.millisAsNanos("initial_duration_ms", NumberWire.INT),
			)
			"codex.turn_ttft" -> common(fields, base).copy(
				eventType = EventType.TURN_FIRST_TOKEN,
				ttftNs = fields.millisAsNanos("duration_ms", NumberWire.DECIMAL_STRING),
				ttftScope = TtftScope.TURN,
			)
			// 프롬프트 제출 관측 — 본문은 싣지 않는다. 고유 턴 수가 아니다.
			"codex.user_prompt" -> common(fields, base).copy(
				eventType = EventType.PROMPT_SUBMITTED,
				promptLength = fields.count("prompt_length", NumberWire.DECIMAL_STRING),
			)
			// 정책·provider·auth 설정은 metadata 에만 — 이 세션의 다른 행으로 옮기지 않는다.
			"codex.conversation_starts" -> common(fields, base).copy(
				eventType = EventType.CONVERSATION_STARTED,
				reasoningEffort = reasoningEffort(fields.text("reasoning_effort")),
			)
			"codex.startup_phase" -> common(fields, base).copy(
				eventType = EventType.STARTUP_PHASE,
				durationNs = fields.millisAsNanos("duration_ms", NumberWire.DECIMAL_STRING),
			)
			"codex.auth_recovery" -> common(fields, base).copy(eventType = EventType.AUTH_EVENT).let {
				val requestId = fields.nonEmptyText("auth.request_id")
				it.copy(requestId = requestId, requestIdNamespace = requestId?.let { UPSTREAM_REQUEST_NAMESPACE })
			}
			// communication ID·kind·state·sender/receiver 만 metadata 에. content 는 allowlist 밖이다.
			"codex.agent_communication" -> common(fields, base).copy(eventType = EventType.AGENT_COMMUNICATION)
			else -> return null
		}
		return MappedEvent(mapped.withFlags(*fields.flags.toTypedArray()))
	}

	/** 모든 매핑 행의 공통 몫 — 의미 확인, 세션, 세션 모델. */
	private fun common(fields: FieldReader, base: EventObservation): EventObservation {
		val session = fields.nonEmptyText("conversation.id")
		return base.copy(
			envelope = base.envelope.copy(
				mappingStatus = MappingStatus.MAPPED,
				sessionId = session,
				sessionIdNamespace = session?.let { SESSION_NAMESPACE },
				model = fields.nonEmptyText("model"),
			),
		)
	}

	/**
	 * `codex.sse_event` 의 네 분기(ADR 0020 부록 A.3) — 위에서부터 처음 맞는 것.
	 *
	 * 기준 키는 `event.kind` 다. 이 버전들의 SSE 로그는 `kind` 를 내지 않으므로 alias 로 읽지 않는다 — 다른 값으로
	 * 함께 오면 `ambiguous_attribute` 이고 완료를 확인하지 못한 것으로 본다. `tool_token_count` 는 tool 성분이 아니라
	 * 응답의 사용량 총계라 `tokens_total_reported` 다(근거 문서 3).
	 */
	private fun sse(fields: FieldReader, common: EventObservation): EventObservation {
		val tokensPresent = TOKEN_KEYS.any { fields.has(it) }
		val errorPresent = fields.has(ERROR_MESSAGE)
		val kind = fields.nonEmptyText("event.kind")
		val alias = fields.text("kind")
		val aliasConflict = alias != null && alias != kind
		val completed = kind == COMPLETED && !aliasConflict

		val input = fields.count(INPUT, NumberWire.DECIMAL_STRING)
		val output = fields.count(OUTPUT, NumberWire.DECIMAL_STRING)
		val cached = fields.count(CACHED, NumberWire.INT)
		val cacheWrite = fields.count(CACHE_WRITE, NumberWire.INT)
		val reasoning = fields.count(REASONING, NumberWire.INT)
		val total = fields.count(TOTAL, NumberWire.DECIMAL_STRING)
		val withTokens: (EventObservation) -> EventObservation = {
			it.copy(
				tokensInput = input, tokensOutput = output, tokensCacheRead = cached, tokensCacheCreate = cacheWrite,
				tokensReasoning = reasoning, tokensTotalReported = total,
			)
		}
		val timed = common.copy(
			durationNs = fields.millisAsNanos("duration_ms", NumberWire.DECIMAL_STRING),
			reasoningEffort = reasoningEffort(fields.text("model_reasoning_effort")),
		)
		val flags = if (aliasConflict) arrayOf(QualityFlag.AMBIGUOUS_ATTRIBUTE) else emptyArray()

		return when {
			// ① 토큰과 오류가 함께 — 어느 쪽인지 정할 수 없다. KPI 밖.
			tokensPresent && errorPresent -> withTokens(timed).copy(
				envelope = timed.envelope.copy(
					mappingStatus = MappingStatus.AMBIGUOUS, usageRole = UsageRole.DIAGNOSTIC, usageScope = UsageScope.RESPONSE,
				),
				eventType = EventType.MODEL_RESPONSE_USAGE,
				errorType = ErrorType.UNKNOWN,
			).withFlags(QualityFlag.AMBIGUOUS_PAYLOAD, *flags)
			// ② 오류만. 오류 원문은 metadata 에도 싣지 않는다 — 검증된 오류 종류가 없어 unknown.
			errorPresent -> timed.copy(eventType = EventType.TRANSPORT_STREAM_ERROR, errorType = ErrorType.UNKNOWN).withFlags(*flags)
			// ③ 확인된 완료 + 유효한 토큰 성분 하나 이상. 0 도 유효한 보고다.
			completed && listOf(input, output, cached, cacheWrite, reasoning).any { it != null } -> withTokens(timed).copy(
				envelope = timed.envelope.copy(usageRole = UsageRole.PRIMARY, usageScope = UsageScope.RESPONSE),
				eventType = EventType.MODEL_RESPONSE_USAGE,
				ttftNs = fields.millisAsNanos("ttft_ms", NumberWire.INT),
				ttftScope = TtftScope.REQUEST,
			).withFlags(*flags)
			// ④ 그 밖의 stream 이벤트. 완료 이름만으로 사용량·성공을 만들지 않는다.
			else -> timed.copy(eventType = EventType.TRANSPORT_STREAM_EVENT).withFlags(*flags)
		}
	}

	/**
	 * `codex.api_request` → `transport.request.attempt`(ADR 0020 부록 A.2). `operation` 은 등록한 소스 위치로만 정한다 —
	 * 모델 목록 emitter 는 `models_list`, 세션 emitter 는 여러 요청 경로가 공유하므로 그 위치의 `endpoint` 가
	 * `/responses` 일 때만 `responses` 다. 위치가 등록되지 않았거나 경로가 다르면 `unknown`. 세션·success 유무로
	 * 추론하지 않는다. 모델 목록 emitter 의 `success` 만 bool 로 오고, 세션 emitter 는 `success` 를 내지 않는다(null).
	 */
	private fun apiRequest(fields: FieldReader, view: LogRecordView, common: EventObservation): EventObservation {
		val endpoint = fields.text("endpoint")
		val operation = when (CodexLocations.apiRequestEmitter(common.envelope.serviceVersion, view.eventName)) {
			CodexLocations.ApiRequestEmitter.MODELS_LIST -> if (endpoint == null || endpoint == MODELS_ENDPOINT) {
				Operation.MODELS_LIST
			} else {
				// 위치와 경로가 서로 다른 요청을 가리킨다 — 어느 쪽도 고르지 않는다.
				return request(fields, common, Operation.UNKNOWN, BoolWire.BOOL).withFlags(QualityFlag.AMBIGUOUS_ATTRIBUTE)
			}
			CodexLocations.ApiRequestEmitter.SESSION_REQUEST -> if (endpoint == RESPONSES_ENDPOINT) Operation.RESPONSES else Operation.UNKNOWN
			null -> Operation.UNKNOWN
		}
		return request(fields, common, operation, BoolWire.BOOL)
	}

	/** `codex.websocket_request` → `transport.request.attempt`. 로그에 경로가 없어 operation 은 `unknown`, success 는 문자열이다. */
	private fun websocketRequest(fields: FieldReader, common: EventObservation): EventObservation =
		request(fields, common, Operation.UNKNOWN, BoolWire.STRING)

	/** `codex.websocket_connect` → `transport.connection`. HTTP 상태와 success 를 서로 대신 채우지 않는다. */
	private fun websocketConnect(fields: FieldReader, common: EventObservation): EventObservation =
		request(fields, common, Operation.NONE, BoolWire.STRING).copy(eventType = EventType.TRANSPORT_CONNECTION)

	/** 요청·연결 공통: duration·HTTP 상태·attempt·명시 success·오류 존재·upstream request ID. */
	private fun request(fields: FieldReader, common: EventObservation, operation: Operation, success: BoolWire): EventObservation {
		val requestId = fields.nonEmptyText("auth.request_id")
		return common.copy(
			eventType = EventType.TRANSPORT_REQUEST_ATTEMPT,
			operation = operation,
			durationNs = fields.millisAsNanos("duration_ms", NumberWire.DECIMAL_STRING),
			httpStatus = httpStatus(fields),
			attempt = fields.count("attempt", NumberWire.INT),
			success = fields.bool("success", success),
			errorType = if (fields.has(ERROR_MESSAGE)) ErrorType.UNKNOWN else ErrorType.NONE,
			requestId = requestId,
			requestIdNamespace = requestId?.let { UPSTREAM_REQUEST_NAMESPACE },
		)
	}

	/** HTTP 상태 코드 — `intValue`, 0–65535. 범위 밖은 invalid. */
	private fun httpStatus(fields: FieldReader): Int? {
		val status = fields.count("http.response.status_code", NumberWire.INT) ?: return null
		return if (status <= UINT16_MAX) status.toInt() else fields.invalid()
	}

	/**
	 * `codex.tool_result` → `tool.result`. `tool_result_seq` 는 producer 프로세스 안의 결과 기록 순번이다 — 같은 `call_id` 의
	 * 다른 순번은 다른 관측이다. 도구 출처는 producer 가 쓰는 규칙 그대로 `mcp_server` 가 비었으면 builtin, 있으면 mcp 다.
	 * 동작(`tool_action`)은 도구 이름별 검증 registry 가 없어 unknown 이다. success 는 문자열 true/false 만 읽는다.
	 */
	private fun toolResult(fields: FieldReader, common: EventObservation): EventObservation {
		val mcpServer = fields.text("mcp_server")
		return common.copy(
			eventType = EventType.TOOL_RESULT,
			callId = callId(fields),
			callIdNamespace = callId(fields)?.let { CALL_NAMESPACE },
			toolResultSeq = fields.count("tool_result_seq", NumberWire.INT),
			toolName = fields.nonEmptyText("tool_name"),
			toolNamespace = fields.nonEmptyText("tool_namespace"),
			mcpServer = mcpServer?.takeIf { it.isNotEmpty() },
			toolOrigin = when {
				mcpServer == null -> ToolOrigin.UNKNOWN
				mcpServer.isEmpty() -> ToolOrigin.BUILTIN
				else -> ToolOrigin.MCP
			},
			toolAction = ToolAction.UNKNOWN,
			durationNs = fields.millisAsNanos("duration_ms", NumberWire.DECIMAL_STRING),
			success = fields.bool("success", BoolWire.STRING),
		)
	}

	/**
	 * `codex.tool_decision` → `tool.decision`. 결정 원문은 `decision_raw` 에 두고, producer 의 결정 표기(근거 문서의
	 * `ReviewDecision` 목록)를 accept·reject·abort 로 옮긴다. 결정하지 못한 시간 초과와 모르는 값은 unknown 이다.
	 * 출처는 producer 의 세 값만 옮기고, 없거나 모르면 unknown 이다 — user·system 으로 채우지 않는다.
	 */
	private fun toolDecision(fields: FieldReader, common: EventObservation): EventObservation {
		val raw = fields.nonEmptyText("decision")
		return common.copy(
			eventType = EventType.TOOL_DECISION,
			callId = callId(fields),
			callIdNamespace = callId(fields)?.let { CALL_NAMESPACE },
			toolName = fields.nonEmptyText("tool_name"),
			toolNamespace = fields.nonEmptyText("tool_namespace"),
			decision = raw?.let { DECISIONS[it] ?: Decision.UNKNOWN } ?: Decision.UNKNOWN,
			decisionRaw = raw,
			decisionSource = DECISION_SOURCES[fields.text("source")] ?: DecisionSource.UNKNOWN,
			decisionScope = DecisionScope.UNKNOWN,
		)
	}

	private fun callId(fields: FieldReader): String? = fields.nonEmptyText("call_id")

	/**
	 * producer 의 추론 강도 표기(`ReasoningEffort` 의 wire 값) 중 이 컬럼의 어휘와 겹치지 않는 값만 옮긴다. 원본의
	 * `none`(추론 없음)은 이 컬럼의 `none`(해당 없음)과 뜻이 달라 `unknown` 으로 두고, 모르는 값도 `unknown` 이다 —
	 * 원래 값은 metadata 에 남는다.
	 */
	private fun reasoningEffort(raw: String?): ReasoningEffort = when {
		raw == null -> ReasoningEffort.NONE
		raw in REASONING_EFFORTS -> ReasoningEffort(raw)
		else -> ReasoningEffort.UNKNOWN
	}

	const val SESSION_NAMESPACE: String = "codex.conversation"

	/** 인증 오류 응답이 실어 온 upstream 요청 ID. */
	const val UPSTREAM_REQUEST_NAMESPACE: String = "codex.upstream_request"
	/** 도구 호출 ID(`call_id`) — producer 가 모델 응답에서 받은 호출 ID 다. */
	const val CALL_NAMESPACE: String = "codex.call"
	private val DECISIONS = mapOf(
		"approved" to Decision.ACCEPT,
		"approved_with_amendment" to Decision.ACCEPT,
		"approved_for_session" to Decision.ACCEPT,
		"approved_mcp_policy_amendment" to Decision.ACCEPT,
		"approved_with_network_policy_allow" to Decision.ACCEPT,
		"denied" to Decision.REJECT,
		"denied_with_network_policy_deny" to Decision.REJECT,
		"abort" to Decision.ABORT,
	)
	private val DECISION_SOURCES = mapOf(
		"Config" to DecisionSource.CONFIG,
		"AutomatedReviewer" to DecisionSource.AUTOMATED_REVIEWER,
		"User" to DecisionSource.USER,
	)
	private const val MODELS_ENDPOINT = "/models"
	private const val RESPONSES_ENDPOINT = "/responses"
	private const val UINT16_MAX = 65_535L
	private const val EVENT_NAME = "event.name"
	private const val ERROR_MESSAGE = "error.message"
	private const val COMPLETED = "response.completed"
	private const val INPUT = "input_token_count"
	private const val OUTPUT = "output_token_count"
	private const val CACHED = "cached_token_count"
	private const val CACHE_WRITE = "cache_write_token_count"
	private const val REASONING = "reasoning_token_count"
	private const val TOTAL = "tool_token_count"
	private val TOKEN_KEYS = listOf(INPUT, OUTPUT, CACHED, CACHE_WRITE, REASONING, TOTAL)
	private val REASONING_EFFORTS = setOf("minimal", "low", "medium", "high", "xhigh", "max", "ultra", "persistent")
}
