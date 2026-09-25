package com.team376.pulsemetry.telemetry.adapter.observation.codex

import com.team376.pulsemetry.telemetry.adapter.observation.ErrorType
import com.team376.pulsemetry.telemetry.adapter.observation.EventObservation
import com.team376.pulsemetry.telemetry.adapter.observation.EventType
import com.team376.pulsemetry.telemetry.adapter.observation.MappingStatus
import com.team376.pulsemetry.telemetry.adapter.observation.MetadataAllowlist
import com.team376.pulsemetry.telemetry.adapter.observation.QualityFlag
import com.team376.pulsemetry.telemetry.adapter.observation.ReasoningEffort
import com.team376.pulsemetry.telemetry.adapter.observation.TtftScope
import com.team376.pulsemetry.telemetry.adapter.observation.UsageRole
import com.team376.pulsemetry.telemetry.adapter.observation.UsageScope
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
