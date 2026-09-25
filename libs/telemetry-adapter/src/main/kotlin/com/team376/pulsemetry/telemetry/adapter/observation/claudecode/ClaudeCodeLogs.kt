package com.team376.pulsemetry.telemetry.adapter.observation.claudecode

import com.team376.pulsemetry.telemetry.adapter.observation.ErrorType
import com.team376.pulsemetry.telemetry.adapter.observation.EventObservation
import com.team376.pulsemetry.telemetry.adapter.observation.EventType
import com.team376.pulsemetry.telemetry.adapter.observation.MappingStatus
import com.team376.pulsemetry.telemetry.adapter.observation.MetadataAllowlist
import com.team376.pulsemetry.telemetry.adapter.observation.QualityFlag
import com.team376.pulsemetry.telemetry.adapter.observation.ReportedCostBasis
import com.team376.pulsemetry.telemetry.adapter.observation.StopReason
import com.team376.pulsemetry.telemetry.adapter.observation.TtftScope
import com.team376.pulsemetry.telemetry.adapter.observation.TypedValue
import com.team376.pulsemetry.telemetry.adapter.observation.UsageRole
import com.team376.pulsemetry.telemetry.adapter.observation.UsageScope
import com.team376.pulsemetry.telemetry.adapter.observation.WorkloadKind
import com.team376.pulsemetry.telemetry.adapter.observation.profile.FieldReader
import com.team376.pulsemetry.telemetry.adapter.observation.profile.LogProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.LogRecordView
import com.team376.pulsemetry.telemetry.adapter.observation.profile.MappedEvent
import com.team376.pulsemetry.telemetry.adapter.observation.profile.NumberWire
import com.team376.pulsemetry.telemetry.adapter.observation.withFlags
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Claude Code 로그 매핑(ADR 0020 부록 A.2).
 *
 * **의미 이름**은 속성 `event.name`(접두사 없음)이다. 같은 이름이 본문에 `claude_code.<이름>` 으로 한 번 더 실린다 — 둘이
 * 가리키는 이름이 다르면 어느 쪽도 고르지 않는다(`ambiguous`, 매핑하지 않음). 본문이 비었거나 없으면 속성만 본다.
 *
 * 세션은 `session.id`, 턴은 `prompt.id`(한 사용자 프롬프트의 처리 범위)다. 필드의 wire 타입은 실캡처가 보인 그대로만 받는다 —
 * 같은 이름(`duration_ms` 등)도 이벤트마다 다르다(근거 문서). 실캡처가 없는 이벤트의 정수 필드는 두 표기를 모두 받는다.
 */
internal object ClaudeCodeLogs : LogProfile {

	override val allowlist: MetadataAllowlist = MetadataAllowlist(
		version = "claude-code-logs-v1",
		resource = setOf("service.name", "service.version", "host.arch", "os.type", "os.version"),
		record = setOf(
			// 공통 — 계정·조직·이메일·사용자 해시는 뺀다(조직 귀속은 installation 이 한다).
			"event.name", "event.timestamp", "event.sequence", "session.id", "prompt.id", "terminal.type", "model",
			// 요청·사용량·비용
			"request_id", "client_request_id", "query_source", "effort", "speed", "agent.name", "skill.name", "plugin.name",
			"marketplace.name", "mcp_server.name", "mcp_tool.name", "input_tokens", "output_tokens", "cache_read_tokens",
			"cache_creation_tokens", "cost_usd", "cost_usd_micros", "duration_ms", "ttft_ms",
			// 오류·재시도·응답·본문 메타데이터 — 오류 원문·본문·body_ref 는 뺀다.
			"status_code", "attempt", "error_class", "total_attempts", "total_retry_duration_ms", "response_length", "category",
			"server_fallback_hop", "truncated",
		),
	)

	override fun semanticName(view: LogRecordView): String? = FieldReader(view.attributes).nonEmptyText(EVENT_NAME)

	override fun map(name: String, view: LogRecordView, base: EventObservation): MappedEvent? {
		val body = (view.body as? TypedValue.Str)?.value
		if (!body.isNullOrEmpty() && body != BODY_PREFIX + name) {
			// 본문과 속성이 다른 이벤트를 가리킨다 — 어느 쪽도 고르지 않는다.
			return MappedEvent(
				base.copy(envelope = base.envelope.copy(mappingStatus = MappingStatus.AMBIGUOUS)).withFlags(QualityFlag.AMBIGUOUS_ATTRIBUTE),
			)
		}
		val fields = FieldReader(view.attributes)
		val mapped = when (name) {
			"api_request" -> apiRequest(fields, common(fields, base))
			// 사용량에 더하지 않는다. 오류와 재시도 고갈을 더해 고유 실패 요청 수라 부르지 않는다 — 각자의 관측이다.
			"api_error" -> common(fields, base).copy(
				eventType = EventType.MODEL_REQUEST_ERROR,
				httpStatus = httpStatus(fields, "status_code"),
				attempt = fields.count("attempt", NumberWire.INT_OR_DECIMAL_STRING),
				durationNs = fields.millisAsNanos("duration_ms", NumberWire.INT_OR_DECIMAL_STRING),
				errorType = if (fields.has("error")) ErrorType.UNKNOWN else ErrorType.NONE,
			)
			"api_retries_exhausted" -> common(fields, base).copy(
				eventType = EventType.MODEL_RETRY_EXHAUSTED,
				attempt = fields.count("total_attempts", NumberWire.INT_OR_DECIMAL_STRING),
				durationNs = fields.millisAsNanos("total_retry_duration_ms", NumberWire.INT_OR_DECIMAL_STRING),
			)
			// 텍스트 응답의 길이·요청 ID 만. 응답 원문은 allowlist 밖이다. 사용량·호출 수에 더하지 않는다.
			"assistant_response" -> common(fields, base).copy(
				eventType = EventType.MODEL_RESPONSE_TEXT,
				responseLength = fields.count("response_length", NumberWire.INT),
			)
			// 거부는 응답의 stop_reason 이 refusal 인 사건이다. 분류(category)는 metadata 에.
			"api_refusal" -> common(fields, base).copy(eventType = EventType.MODEL_RESPONSE_REFUSAL, stopReason = REFUSAL)
			// 본문 이벤트는 요청 ID·attempt·잘림 여부만. 본문과 body_ref 가 가리키는 파일은 읽지 않는다.
			"api_request_body", "api_response_body" -> common(fields, base).copy(
				eventType = EventType.MODEL_BODY_METADATA,
				attempt = fields.count("attempt", NumberWire.INT_OR_DECIMAL_STRING),
			)
			else -> return null
		}
		return MappedEvent(mapped.withFlags(*fields.flags.toTypedArray()))
	}

	/** 모든 매핑 행의 공통 몫 — 의미 확인, 세션·턴·모델·요청 ID. */
	private fun common(fields: FieldReader, base: EventObservation): EventObservation {
		val session = fields.nonEmptyText("session.id")
		val prompt = fields.nonEmptyText("prompt.id")
		val request = fields.nonEmptyText("request_id")
		val clientRequest = fields.nonEmptyText("client_request_id")
		return base.copy(
			envelope = base.envelope.copy(
				mappingStatus = MappingStatus.MAPPED,
				sessionId = session,
				sessionIdNamespace = session?.let { SESSION_NAMESPACE },
				model = fields.nonEmptyText("model"),
			),
			turnId = prompt,
			turnIdNamespace = prompt?.let { PROMPT_NAMESPACE },
			requestId = request,
			requestIdNamespace = request?.let { REQUEST_NAMESPACE },
			clientRequestId = clientRequest,
			clientRequestIdNamespace = clientRequest?.let { CLIENT_REQUEST_NAMESPACE },
		)
	}

	/**
	 * `api_request` → `model.response.usage`. 유효한 토큰 성분이나 보고 비용이 하나라도 있으면 primary/response, 없으면
	 * 이름만으로 사용량을 만들지 않는다(usage_role none). 비용만 있으면 토큰은 null 이다.
	 *
	 * 비용은 벤더의 추정값이다(`reported_cost_basis = estimate`). `cost_usd_micros`(정수 백만분의 일 달러)가 있으면 그것을
	 * 정확한 Decimal 로 쓰고, `cost_usd`(double)와 **1 마이크로달러**보다 크게 어긋나면 보고 금액 null + `cost_conflict` 다
	 * (토큰은 유지). double 만 있으면 컬럼 정밀도(소수 12자리)로 반올림한다.
	 */
	private fun apiRequest(fields: FieldReader, common: EventObservation): EventObservation {
		val input = fields.count("input_tokens", NumberWire.INT)
		val output = fields.count("output_tokens", NumberWire.INT)
		val cacheRead = fields.count("cache_read_tokens", NumberWire.INT)
		val cacheCreate = fields.count("cache_creation_tokens", NumberWire.INT)
		val (cost, conflict) = cost(fields)
		val reported = listOf(input, output, cacheRead, cacheCreate).any { it != null } || cost != null
		val observation = common.copy(
			envelope = common.envelope.copy(
				usageRole = if (reported) UsageRole.PRIMARY else UsageRole.NONE,
				usageScope = UsageScope.RESPONSE,
				workloadKind = workload(fields.nonEmptyText("query_source")),
			),
			eventType = EventType.MODEL_RESPONSE_USAGE,
			tokensInput = input,
			tokensOutput = output,
			tokensCacheRead = cacheRead,
			tokensCacheCreate = cacheCreate,
			costReportedUsd = cost,
			reportedCostBasis = if (cost != null) ReportedCostBasis.ESTIMATE else ReportedCostBasis.UNKNOWN,
			durationNs = fields.millisAsNanos("duration_ms", NumberWire.INT),
			ttftNs = fields.millisAsNanos("ttft_ms", NumberWire.INT),
			ttftScope = if (fields.has("ttft_ms")) TtftScope.REQUEST else TtftScope.NONE,
		)
		return if (conflict) observation.withFlags(QualityFlag.COST_CONFLICT) else observation
	}

	private fun cost(fields: FieldReader): Pair<BigDecimal?, Boolean> {
		val usd = fields.nonNegativeDouble("cost_usd")
		val micros = fields.count("cost_usd_micros", NumberWire.INT)?.let { BigDecimal.valueOf(it).movePointLeft(MICROS_SCALE) }
		return when {
			micros != null && usd != null && (usd - micros).abs() > COST_TOLERANCE -> null to true
			micros != null -> micros to false
			usd != null -> usd.setScale(COLUMN_SCALE, RoundingMode.HALF_EVEN) to false
			else -> null to false
		}
	}

	/** HTTP 상태 코드(0–65535). 범위 밖은 invalid. */
	private fun httpStatus(fields: FieldReader, key: String): Int? {
		val status = fields.count(key, NumberWire.INT_OR_DECIMAL_STRING) ?: return null
		return if (status <= UINT16_MAX) status.toInt() else fields.invalid()
	}

	/**
	 * `query_source` 로 작업 목적을 정한다. 메인 대화(`repl_main_thread`·`main`)는 main, `compact` 는 compaction, 하위
	 * 에이전트(`subagent`, 에이전트 이름을 담은 `agent:…`)는 subagent. 제목 생성·요약 같은 보조 작업과 모르는 값은 unknown.
	 */
	private fun workload(querySource: String?): WorkloadKind = when {
		querySource == null -> WorkloadKind.UNKNOWN
		querySource == "repl_main_thread" || querySource == "main" -> WorkloadKind.MAIN
		querySource == "compact" -> WorkloadKind.COMPACTION
		querySource == "subagent" || querySource.startsWith("agent:") -> WorkloadKind.SUBAGENT
		else -> WorkloadKind.UNKNOWN
	}

	const val SESSION_NAMESPACE: String = "claude_code.session"

	/** 한 사용자 프롬프트의 처리 범위(`prompt.id`). */
	const val PROMPT_NAMESPACE: String = "claude_code.prompt"

	/** API 응답 헤더의 요청 ID. */
	const val REQUEST_NAMESPACE: String = "claude_code.request"

	/** 클라이언트가 만든 요청 ID. */
	const val CLIENT_REQUEST_NAMESPACE: String = "claude_code.client_request"

	private val REFUSAL = StopReason("refusal")
	private const val UINT16_MAX = 65_535L
	private const val EVENT_NAME = "event.name"
	private const val BODY_PREFIX = "claude_code."
	private const val MICROS_SCALE = 6
	private const val COLUMN_SCALE = 12
	private val COST_TOLERANCE = BigDecimal("0.000001")
}
