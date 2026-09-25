package com.team376.pulsemetry.telemetry.adapter.observation.claudecode

import com.team376.pulsemetry.telemetry.adapter.observation.ErrorType
import com.team376.pulsemetry.telemetry.adapter.observation.EventObservation
import com.team376.pulsemetry.telemetry.adapter.observation.EventType
import com.team376.pulsemetry.telemetry.adapter.observation.MappingStatus
import com.team376.pulsemetry.telemetry.adapter.observation.MetadataAllowlist
import com.team376.pulsemetry.telemetry.adapter.observation.StopReason
import com.team376.pulsemetry.telemetry.adapter.observation.ToolAction
import com.team376.pulsemetry.telemetry.adapter.observation.ToolOrigin
import com.team376.pulsemetry.telemetry.adapter.observation.TtftScope
import com.team376.pulsemetry.telemetry.adapter.observation.UsageRole
import com.team376.pulsemetry.telemetry.adapter.observation.UsageScope
import com.team376.pulsemetry.telemetry.adapter.observation.profile.BoolWire
import com.team376.pulsemetry.telemetry.adapter.observation.profile.FieldReader
import com.team376.pulsemetry.telemetry.adapter.observation.profile.MappedEvent
import com.team376.pulsemetry.telemetry.adapter.observation.profile.NumberWire
import com.team376.pulsemetry.telemetry.adapter.observation.profile.SpanProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.SpanView
import com.team376.pulsemetry.telemetry.adapter.observation.withFlags

/**
 * Claude Code 스팬 허용 목록(ADR 0020 부록 A.4). 여섯 이름이 행이 되고 그 밖은 제외 수로 센다. 각 스팬은 자기 구간을 따로
 * 보존한다 — 상위 전체 시간(`tool`), 실행 시간(`tool.execution`), 승인 대기(`tool.blocked_on_user`), 훅 시간(`hook`).
 *
 * `llm_request` 의 토큰은 같은 요청의 로그(`api_request`)와 같은 값이다 — 진단용이며 사용량에 더하지 않는다. 도구 이름 컬럼은
 * 사용자 지정 이름이 없는 `tool_name_safe` 를 쓴다(스팬의 `tool_name` 은 MCP 서버 이름을 그대로 싣는다).
 */
internal object ClaudeCodeSpans : SpanProfile {

	private const val INTERACTION = "claude_code.interaction"
	private const val LLM_REQUEST = "claude_code.llm_request"
	private const val TOOL = "claude_code.tool"
	private const val TOOL_EXECUTION = "claude_code.tool.execution"
	private const val TOOL_WAIT = "claude_code.tool.blocked_on_user"
	private const val HOOK = "claude_code.hook"
	private val NAMES = setOf(INTERACTION, LLM_REQUEST, TOOL, TOOL_EXECUTION, TOOL_WAIT, HOOK)

	override val allowlist: MetadataAllowlist = MetadataAllowlist(
		version = "claude-code-spans-v1",
		resource = setOf("service.name", "service.version", "host.arch", "os.type", "os.version"),
		record = setOf(
			"session.id", "span.type", "model", "gen_ai.system", "gen_ai.request.model", "gen_ai.response.finish_reasons", "llm_request.context",
			"speed", "effort", "query_source_safe", "duration_ms", "input_tokens", "output_tokens", "cache_read_tokens", "cache_creation_tokens",
			"success", "attempt", "error_class", "request_id", "client_request_id", "ttft_ms", "first_content_ms", "stop_reason",
			"interaction.sequence", "interaction.duration_ms", "user_prompt_length", "parent.source", "queued_sends",
			"tool_name_safe", "tool_use_id", "gen_ai.tool.call.id", "bash_command_class", "subagent_type", "decision", "source",
		),
	)

	override fun allows(view: SpanView): Boolean = view.name in NAMES

	override fun map(view: SpanView, base: EventObservation): MappedEvent {
		val fields = FieldReader(view.attributes)
		val session = fields.nonEmptyText("session.id")
		val common = base.copy(
			envelope = base.envelope.copy(
				mappingStatus = MappingStatus.MAPPED,
				sessionId = session,
				sessionIdNamespace = session?.let { ClaudeCodeLogs.SESSION_NAMESPACE },
				model = fields.nonEmptyText("model"),
			),
		)
		val mapped = when (view.name) {
			INTERACTION -> common.copy(eventType = EventType.TURN, promptLength = fields.count("user_prompt_length", NumberWire.INT))
			LLM_REQUEST -> llmRequest(fields, common)
			TOOL -> tool(fields, common).copy(eventType = EventType.TOOL)
			TOOL_EXECUTION -> tool(fields, common).copy(
				eventType = EventType.TOOL_EXECUTION,
				success = fields.bool("success", BoolWire.BOOL),
				errorType = errorClass(fields),
			)
			TOOL_WAIT -> tool(fields, common).copy(eventType = EventType.TOOL_WAIT)
			HOOK -> common.copy(eventType = EventType.HOOK)
			else -> error("허용 목록 밖의 스팬이다: ${view.name}")
		}
		return MappedEvent(mapped.withFlags(*fields.flags.toTypedArray()))
	}

	/** 모델 요청 한 건 — 토큰이 있으면 진단용 response 단위, TTFT 는 요청 단위. */
	private fun llmRequest(fields: FieldReader, common: EventObservation): EventObservation {
		val input = fields.count("input_tokens", NumberWire.INT)
		val output = fields.count("output_tokens", NumberWire.INT)
		val cacheRead = fields.count("cache_read_tokens", NumberWire.INT)
		val cacheCreate = fields.count("cache_creation_tokens", NumberWire.INT)
		val tokens = listOf(input, output, cacheRead, cacheCreate).any { it != null }
		val request = fields.nonEmptyText("request_id")
		val clientRequest = fields.nonEmptyText("client_request_id")
		return common.copy(
			envelope = common.envelope.copy(
				usageRole = if (tokens) UsageRole.DIAGNOSTIC else UsageRole.NONE,
				usageScope = UsageScope.RESPONSE,
			),
			eventType = EventType.MODEL_REQUEST,
			tokensInput = input,
			tokensOutput = output,
			tokensCacheRead = cacheRead,
			tokensCacheCreate = cacheCreate,
			ttftNs = fields.millisAsNanos("ttft_ms", NumberWire.INT),
			ttftScope = if (fields.has("ttft_ms")) TtftScope.REQUEST else TtftScope.NONE,
			attempt = fields.count("attempt", NumberWire.INT),
			success = fields.bool("success", BoolWire.BOOL),
			stopReason = fields.nonEmptyText("stop_reason")?.let { if (it in STOP_REASONS) StopReason(it) else StopReason.UNKNOWN } ?: StopReason.NONE,
			errorType = errorClass(fields),
			requestId = request,
			requestIdNamespace = request?.let { ClaudeCodeLogs.REQUEST_NAMESPACE },
			clientRequestId = clientRequest,
			clientRequestIdNamespace = clientRequest?.let { ClaudeCodeLogs.CLIENT_REQUEST_NAMESPACE },
		)
	}

	/** 도구 스팬 공통 — 호출 ID(`tool_use_id` = `gen_ai.tool.call.id`, 같을 때만), 안전한 도구 이름, 출처·동작. */
	private fun tool(fields: FieldReader, common: EventObservation): EventObservation {
		val callId = fields.aliasedText("tool_use_id", "gen_ai.tool.call.id")
		val safeName = fields.nonEmptyText("tool_name_safe")
		val mcp = safeName == MCP_OTHER || ClaudeCodeTools.isMcpName(fields.nonEmptyText("tool_name"))
		return common.copy(
			callId = callId,
			callIdNamespace = callId?.let { ClaudeCodeLogs.TOOL_USE_NAMESPACE },
			toolName = safeName,
			toolOrigin = if (safeName == null && !mcp) ToolOrigin.NONE
			else ClaudeCodeTools.origin(safeName, if (mcp) "mcp" else null),
			toolAction = if (safeName == null && !mcp) ToolAction.NONE
			else ClaudeCodeTools.action(safeName, mcp),
		)
	}

	/** 식별자 꼴의 오류 분류만 옮긴다. 오류 원문(`error`)은 싣지 않는다. */
	private fun errorClass(fields: FieldReader): ErrorType {
		val value = fields.nonEmptyText("error_class") ?: return if (fields.has("error")) ErrorType.UNKNOWN else ErrorType.NONE
		return if (ERROR_CLASS.matches(value)) ErrorType(value) else ErrorType.UNKNOWN
	}

	/** producer 가 MCP 도구 이름을 가린 표기. */
	private const val MCP_OTHER = "mcp_other"
	private val ERROR_CLASS = Regex("[A-Za-z0-9_:.\\-]{1,64}")

	/** API 응답의 stop_reason 값(근거 문서 8). */
	private val STOP_REASONS = setOf("end_turn", "tool_use", "max_tokens", "stop_sequence", "pause_turn", "refusal")
}
