package com.team376.pulsemetry.telemetry.adapter.observation.codex

import com.team376.pulsemetry.telemetry.adapter.observation.ErrorType
import com.team376.pulsemetry.telemetry.adapter.observation.EventObservation
import com.team376.pulsemetry.telemetry.adapter.observation.EventType
import com.team376.pulsemetry.telemetry.adapter.observation.MappingStatus
import com.team376.pulsemetry.telemetry.adapter.observation.MetadataAllowlist
import com.team376.pulsemetry.telemetry.adapter.observation.ToolAction
import com.team376.pulsemetry.telemetry.adapter.observation.ToolOrigin
import com.team376.pulsemetry.telemetry.adapter.observation.UsageRole
import com.team376.pulsemetry.telemetry.adapter.observation.UsageScope
import com.team376.pulsemetry.telemetry.adapter.observation.WorkloadKind
import com.team376.pulsemetry.telemetry.adapter.observation.profile.FieldReader
import com.team376.pulsemetry.telemetry.adapter.observation.profile.MappedEvent
import com.team376.pulsemetry.telemetry.adapter.observation.profile.NumberWire
import com.team376.pulsemetry.telemetry.adapter.observation.profile.SpanProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.SpanView
import com.team376.pulsemetry.telemetry.adapter.observation.withFlags

/**
 * Codex 스팬 허용 목록(ADR 0020 부록 A.4). 네 이름만 행이 되고, 그 밖의 스팬은 아카이브에만 남고 제외 수로 센다.
 *
 * - 작업 스팬(`session_task.turn`·`session_task.compact`)은 `turn.id`·모델·추론 강도와 **그 턴 동안의 토큰 증분**을
 *   싣는다. 턴 토큰은 `usage_scope = turn`, diagnostic 이며 응답 사용량에 더하지 않는다. 이 스팬의 `thread.id` 는 세션
 *   ID 로 쓰지 않는다 — 세션은 null 이다.
 * - `try_run_sampling_request` 는 `turn_id`(같은 턴 ID 의 다른 키 표기)와 모델만 싣는다.
 * - `mcp.tools.call` 의 `tool.call_id` 는 producer 가 도구 결과 로그의 `call_id` 와 같은 호출 ID 를 넘긴다 — 같은
 *   namespace 다. `conversation.id`·`session.id` 는 같은 값을 싣는다(같을 때만 세션).
 * - 일반 도구 스팬(`handle_tool_call` 등)은 도구 이름과 호출 ID 를 싣지 않아 도구 실행으로 만들지 않는다.
 *
 * 부모를 받지 못한 스팬이 root 라는 뜻은 아니고, status `unset` 은 성공이 아니다 — 둘 다 원래대로 둔다.
 */
internal object CodexSpans : SpanProfile {

	private const val TURN = "session_task.turn"
	private const val COMPACT = "session_task.compact"
	private const val SAMPLING = "try_run_sampling_request"
	private const val MCP_CALL = "mcp.tools.call"
	private val NAMES = setOf(TURN, COMPACT, SAMPLING, MCP_CALL)
	private const val TOKENS = "codex.turn.token_usage."

	/** 턴 ID(작업 스팬 `turn.id` = 요청 스팬 `turn_id` = MCP 스팬 `turn.id`). */
	const val TURN_NAMESPACE: String = "codex.turn"

	override val allowlist: MetadataAllowlist = MetadataAllowlist(
		version = "codex-spans-v1",
		resource = setOf("service.name", "service.version", "env", "telemetry.sdk.name", "telemetry.sdk.language", "telemetry.sdk.version"),
		record = setOf(
			"turn.id", "turn_id", "model", "codex.turn.reasoning_effort",
			"${TOKENS}input_tokens", "${TOKENS}cached_input_tokens", "${TOKENS}cache_write_input_tokens", "${TOKENS}non_cached_input_tokens",
			"${TOKENS}output_tokens", "${TOKENS}reasoning_output_tokens", "${TOKENS}total_tokens",
			"conversation.id", "session.id", "rpc.system", "rpc.method", "mcp.transport", "mcp.server.name", "tool.name", "tool.call_id",
			"error.type", "codex.mcp.error.code",
		),
	)

	override fun allows(view: SpanView): Boolean = view.name in NAMES

	override fun map(view: SpanView, base: EventObservation): MappedEvent {
		val fields = FieldReader(view.attributes)
		val turnId = fields.aliasedText("turn.id", "turn_id")
		val common = base.copy(
			envelope = base.envelope.copy(mappingStatus = MappingStatus.MAPPED, model = fields.nonEmptyText("model")),
			turnId = turnId,
			turnIdNamespace = turnId?.let { TURN_NAMESPACE },
		)
		val mapped = when (view.name) {
			TURN -> turn(fields, common, EventType.TURN, WorkloadKind.UNKNOWN)
			COMPACT -> turn(fields, common, EventType.CONTEXT_COMPACTION, WorkloadKind.COMPACTION)
			SAMPLING -> common.copy(eventType = EventType.MODEL_REQUEST)
			MCP_CALL -> mcpCall(fields, common)
			else -> error("허용 목록 밖의 스팬이다: ${view.name}")
		}
		return MappedEvent(mapped.withFlags(*fields.flags.toTypedArray()))
	}

	/** 작업 스팬 — 턴 토큰 증분은 진단용이다. `total_tokens` 는 원본이 총계로 정의한 필드다. */
	private fun turn(fields: FieldReader, common: EventObservation, type: EventType, workload: WorkloadKind): EventObservation =
		common.copy(
			envelope = common.envelope.copy(usageRole = UsageRole.DIAGNOSTIC, usageScope = UsageScope.TURN, workloadKind = workload),
			eventType = type,
			tokensInput = fields.count("${TOKENS}input_tokens", NumberWire.INT),
			tokensCacheRead = fields.count("${TOKENS}cached_input_tokens", NumberWire.INT),
			tokensCacheCreate = fields.count("${TOKENS}cache_write_input_tokens", NumberWire.INT),
			tokensOutput = fields.count("${TOKENS}output_tokens", NumberWire.INT),
			tokensReasoning = fields.count("${TOKENS}reasoning_output_tokens", NumberWire.INT),
			tokensTotalReported = fields.count("${TOKENS}total_tokens", NumberWire.INT),
		)

	private fun mcpCall(fields: FieldReader, common: EventObservation): EventObservation {
		val session = fields.aliasedText("conversation.id", "session.id")
		val callId = fields.nonEmptyText("tool.call_id")
		return common.copy(
			envelope = common.envelope.copy(sessionId = session, sessionIdNamespace = session?.let { CodexLogs.SESSION_NAMESPACE }),
			eventType = EventType.TOOL_EXECUTION,
			callId = callId,
			callIdNamespace = callId?.let { CodexLogs.CALL_NAMESPACE },
			toolName = fields.nonEmptyText("tool.name"),
			mcpServer = fields.nonEmptyText("mcp.server.name"),
			toolOrigin = ToolOrigin.MCP,
			toolAction = ToolAction.UNKNOWN,
			errorType = if (fields.nonEmptyText("error.type") != null) ErrorType.UNKNOWN else ErrorType.NONE,
		)
	}
}
