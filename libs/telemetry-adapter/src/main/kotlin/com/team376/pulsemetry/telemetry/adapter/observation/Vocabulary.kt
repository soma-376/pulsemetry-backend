package com.team376.pulsemetry.telemetry.adapter.observation

/**
 * 분석 테이블의 분류 컬럼 어휘. 값의 소유자는 ADR 0020 부록 A 다 — 여기는 그 표를 코드로 옮긴 것이고,
 * 값을 더하거나 바꾸려면 그 ADR 을 먼저 개정한다.
 *
 * **[wire] 가 계약이다.** 적재가 이 문자열을 `LowCardinality(String)` 컬럼에 그대로 쓴다.
 * `none` 은 "이 관측에 해당하지 않음", `unknown` 은 "해당하지만 근거가 없음"이다 — 바꿔 쓰지 않는다.
 *
 * 닫힌 어휘는 enum 이고, 프로파일이 검증해 값을 더하는 열린 어휘(부록 A.1 의 "검증된 값")는
 * `none`·`unknown` 상수를 가진 값 클래스다.
 */
public interface WireValue {
	public val wire: String
}

/** [wire] 로 닫힌 어휘의 상수를 찾는다. 없으면 null. */
public inline fun <reified E> wireValueOf(wire: String): E? where E : Enum<E>, E : WireValue =
	enumValues<E>().firstOrNull { it.wire == wire }

// ── 존속·품질 ──────────────────────────────────────────────────────────────

public enum class RecordStatus(override val wire: String) : WireValue {
	ACTIVE("active"),
	EXCLUDED("excluded"),
}

public enum class MappingStatus(override val wire: String) : WireValue {
	/** 해당 관측의 의미를 확인했다. */
	MAPPED("mapped"),

	/** 안전한 구조만 받아들였다. */
	GENERIC("generic"),

	/** 서로 다른 근거가 충돌한다. */
	AMBIGUOUS("ambiguous"),

	/** 분류를 신뢰할 수 없는 구조 오류다. */
	INVALID("invalid"),
}

public enum class SourceIdentityKind(override val wire: String) : WireValue {
	NATIVE_ID("native_id"),
	FINGERPRINT("fingerprint"),
}

public enum class SourceTimeOrigin(override val wire: String) : WireValue {
	OTLP_EVENT("otlp_event"),
	OTLP_OBSERVED("otlp_observed"),
	SPAN_START("span_start"),
	METRIC_POINT("metric_point"),
}

/** 행 단위 품질 사실(ADR 0020 부록 A.6). API 가 null 의 사유로 내는 코드와는 다른 어휘다. */
public enum class QualityFlag(override val wire: String) : WireValue {
	ARCHIVE_RECEIPT_MISSING("archive_receipt_missing"),
	AMBIGUOUS_ATTRIBUTE("ambiguous_attribute"),
	AMBIGUOUS_PAYLOAD("ambiguous_payload"),
	INVALID_MEASUREMENT("invalid_measurement"),
	DERIVED_VALUE_INVALID("derived_value_invalid"),
	USAGE_SEMANTICS_UNVERIFIED("usage_semantics_unverified"),
	COST_CONFLICT("cost_conflict"),
	NON_FINITE_VALUE("non_finite_value"),
	PROVIDER_UNRESOLVED("provider_unresolved"),
	EVENT_TIME_MISMATCH("event_time_mismatch"),
	INVALID_RELATION_ID("invalid_relation_id"),
	IDENTITY_MATERIAL_MASKED("identity_material_masked"),
	MEMBER_UNRESOLVED("member_unresolved"),
	MULTI_TEAM_MEMBERSHIP("multi_team_membership"),
}

// ── 출처 ──────────────────────────────────────────────────────────────────

/** 분석 테이블의 `signal`. 수집 단계의 시그널 타입과는 다른 타입이다 — 단계 간 의존이 없다. */
public enum class ObservationSignal(override val wire: String) : WireValue {
	LOG("log"),
	SPAN("span"),
	METRIC("metric"),
}

public enum class Product(override val wire: String) : WireValue {
	CLAUDE_CODE("claude_code"),
	CODEX("codex"),
	UNKNOWN("unknown"),
}

public enum class Surface(override val wire: String) : WireValue {
	CLI("cli"),
	DESKTOP("desktop"),
	APP_SERVER("app_server"),
	UNKNOWN("unknown"),
}

// ── 사용량 분류 ─────────────────────────────────────────────────────────────

public enum class UsageRole(override val wire: String) : WireValue {
	PRIMARY("primary"),
	DIAGNOSTIC("diagnostic"),
	NONE("none"),
}

public enum class UsageScope(override val wire: String) : WireValue {
	RESPONSE("response"),
	TURN("turn"),
	INTERVAL("interval"),
	UNKNOWN("unknown"),
}

public enum class WorkloadKind(override val wire: String) : WireValue {
	MAIN("main"),
	SUBAGENT("subagent"),
	COMPACTION("compaction"),
	MEMORY("memory"),
	GUARDIAN("guardian"),
	UNKNOWN("unknown"),
}

public enum class InputSemantics(override val wire: String) : WireValue {
	INCLUSIVE_CACHE("inclusive_cache"),
	EXCLUSIVE_CACHE("exclusive_cache"),
	UNKNOWN("unknown"),
}

public enum class OutputSemantics(override val wire: String) : WireValue {
	/** 보고된 reasoning·tool 성분은 output 의 부분집합이다 — 다시 더하지 않는다. */
	INCLUSIVE_REASONING_TOOL("inclusive_reasoning_tool"),

	/** reasoning·tool 은 output 밖의 별도 성분이다. */
	EXCLUSIVE_REASONING_TOOL("exclusive_reasoning_tool"),
	UNKNOWN("unknown"),
}

public enum class ReportedCostBasis(override val wire: String) : WireValue {
	ESTIMATE("estimate"),
	BILLED("billed"),
	UNKNOWN("unknown"),
}

// ── 이벤트 ────────────────────────────────────────────────────────────────

/** `telemetry_events.event_type`. ADR 0020 부록 A.2(로그)·A.4(스팬)의 표 그대로다. */
public enum class EventType(override val wire: String) : WireValue {
	// 로그
	MODEL_RESPONSE_USAGE("model.response.usage"),
	MODEL_REQUEST_ERROR("model.request.error"),
	MODEL_RETRY_EXHAUSTED("model.retry.exhausted"),
	MODEL_RESPONSE_TEXT("model.response.text"),
	MODEL_RESPONSE_REFUSAL("model.response.refusal"),
	MODEL_BODY_METADATA("model.body.metadata"),
	TRANSPORT_STREAM_ERROR("transport.stream.error"),
	TRANSPORT_STREAM_EVENT("transport.stream.event"),
	TRANSPORT_REQUEST_ATTEMPT("transport.request.attempt"),
	TRANSPORT_CONNECTION("transport.connection"),
	PROMPT_SUBMITTED("prompt.submitted"),
	TOOL_RESULT("tool.result"),
	TOOL_DECISION("tool.decision"),
	SANDBOX_OUTCOME("sandbox.outcome"),
	TURN_FIRST_TOKEN("turn.first_token"),
	CONVERSATION_STARTED("conversation.started"),
	STARTUP_PHASE("startup.phase"),
	AUTH_EVENT("auth.event"),
	AGENT_COMMUNICATION("agent.communication"),
	CONTEXT_COMPACTED("context.compacted"),
	AGENT_COMPLETED("agent.completed"),
	SKILL_ACTIVATED("skill.activated"),
	PERMISSION_CHANGED("permission.changed"),
	MCP_CONNECTION("mcp.connection"),
	HOOK_REGISTERED("hook.registered"),
	HOOK_STARTED("hook.started"),
	HOOK_COMPLETED("hook.completed"),
	VENDOR_UNKNOWN("vendor.unknown"),
	DIAGNOSTIC("diagnostic"),

	// 스팬
	TURN("turn"),
	MODEL_REQUEST("model.request"),
	TOOL("tool"),
	TOOL_EXECUTION("tool.execution"),
	TOOL_WAIT("tool.wait"),
	HOOK("hook"),
	CONTEXT_COMPACTION("context.compaction"),
}

public enum class Operation(override val wire: String) : WireValue {
	MODELS_LIST("models_list"),
	RESPONSES("responses"),
	UNKNOWN("unknown"),
	NONE("none"),
}

public enum class TtftScope(override val wire: String) : WireValue {
	/** API 요청 한 건의 첫 토큰. */
	REQUEST("request"),

	/** 턴의 첫 토큰. 요청 TTFT 와 한 분포로 섞지 않는다. */
	TURN("turn"),
	UNKNOWN("unknown"),

	/** TTFT 가 없는 관측. */
	NONE("none"),
}

public enum class SpanKind(override val wire: String) : WireValue {
	INTERNAL("internal"),
	SERVER("server"),
	CLIENT("client"),
	PRODUCER("producer"),
	CONSUMER("consumer"),
	UNSPECIFIED("unspecified"),
	NONE("none"),
}

public enum class SpanStatusCode(override val wire: String) : WireValue {
	/** 성공이 아니다. */
	UNSET("unset"),
	OK("ok"),
	ERROR("error"),
	NONE("none"),
}

public enum class ToolOrigin(override val wire: String) : WireValue {
	BUILTIN("builtin"),
	MCP("mcp"),
	AGENT("agent"),
	UNKNOWN("unknown"),
	NONE("none"),
}

public enum class ToolAction(override val wire: String) : WireValue {
	READ("read"),
	WRITE("write"),
	EDIT("edit"),
	SEARCH("search"),
	EXEC("exec"),
	FETCH("fetch"),
	UNKNOWN("unknown"),
	NONE("none"),
}

/** 정규화한 결정. 원래 값은 `decision_raw` 에 남는다. */
public enum class Decision(override val wire: String) : WireValue {
	ACCEPT("accept"),
	REJECT("reject"),
	ABORT("abort"),
	UNKNOWN("unknown"),
	NONE("none"),
}

// ── 열린 어휘 — 프로파일이 검증한 값만 더한다 ──────────────────────────────────

@JvmInline
public value class DecisionSource(override val wire: String) : WireValue {
	public companion object {
		public val CONFIG: DecisionSource = DecisionSource("config")
		public val AUTOMATED_REVIEWER: DecisionSource = DecisionSource("automated_reviewer")

		/** producer 가 사용자 결정이라고 명시한 값. 모르는 출처를 이것으로 바꾸지 않는다. */
		public val USER: DecisionSource = DecisionSource("user")
		public val UNKNOWN: DecisionSource = DecisionSource("unknown")
		public val NONE: DecisionSource = DecisionSource("none")
	}
}

/** 결정이 미치는 범위. 한 번·세션·영구 같은 값은 검증 뒤에만 더한다. */
@JvmInline
public value class DecisionScope(override val wire: String) : WireValue {
	public companion object {
		public val UNKNOWN: DecisionScope = DecisionScope("unknown")
		public val NONE: DecisionScope = DecisionScope("none")
	}
}

/** 검증된 오류 종류. 자유 형식 메시지는 값이 될 수 없다. */
@JvmInline
public value class ErrorType(override val wire: String) : WireValue {
	public companion object {
		public val UNKNOWN: ErrorType = ErrorType("unknown")
		public val NONE: ErrorType = ErrorType("none")
	}
}

@JvmInline
public value class StopReason(override val wire: String) : WireValue {
	public companion object {
		public val UNKNOWN: StopReason = StopReason("unknown")
		public val NONE: StopReason = StopReason("none")
	}
}

@JvmInline
public value class ReasoningEffort(override val wire: String) : WireValue {
	public companion object {
		public val UNKNOWN: ReasoningEffort = ReasoningEffort("unknown")
		public val NONE: ReasoningEffort = ReasoningEffort("none")
	}
}

/** 검증된 registry 단위. 이름 접미사로 단위를 추정하지 않는다. */
@JvmInline
public value class CanonicalUnit(override val wire: String) : WireValue {
	public companion object {
		public val UNKNOWN: CanonicalUnit = CanonicalUnit("unknown")
	}
}

// ── 메트릭 ────────────────────────────────────────────────────────────────

public enum class MetricType(override val wire: String) : WireValue {
	GAUGE("gauge"),
	SUM("sum"),
	HISTOGRAM("histogram"),
	EXPONENTIAL_HISTOGRAM("exponential_histogram"),
	SUMMARY("summary"),
}

public enum class Temporality(override val wire: String) : WireValue {
	DELTA("delta"),
	CUMULATIVE("cumulative"),

	/** 저장하되 구간 KPI 에서 뺀다. */
	UNSPECIFIED("unspecified"),

	/** gauge·summary. */
	NONE("none"),
}

/** `telemetry_metric_points.metric_family`. ADR 0020 부록 A.5 의 표 그대로다. */
public enum class MetricFamily(override val wire: String) : WireValue {
	SESSION_STARTED("session.started"),
	CODE_LINES_CHANGED("code.lines.changed"),
	SCM_PULL_REQUEST_CREATED("scm.pull_request.created"),
	SCM_COMMIT_CREATED("scm.commit.created"),
	COST_REPORTED("cost.reported"),
	TOKEN_USAGE("token.usage"),
	TOOL_EDIT_DECISION_COUNT("tool.edit.decision.count"),
	ACTIVITY_ACTIVE_DURATION("activity.active.duration"),
	TOOL_CALLS_PER_TURN("tool.calls.per_turn"),
	TOOL_EXECUTION_COUNT("tool.execution.count"),
	TOOL_EXECUTION_DURATION("tool.execution.duration"),
	TURN_DURATION("turn.duration"),
	TURN_FIRST_TOKEN("turn.first_token"),
	TURN_FIRST_ITEM("turn.first_item"),
	CONVERSATION_STARTED("conversation.started"),
	CONVERSATION_TURNS("conversation.turns"),
	VENDOR_METRIC("vendor.metric"),
}

public enum class TokenComponent(override val wire: String) : WireValue {
	INPUT("input"),
	OUTPUT("output"),
	CACHE_READ("cache_read"),
	CACHE_CREATE("cache_create"),
	REASONING("reasoning"),
	TOOL("tool"),
	UNKNOWN("unknown"),
	NONE("none"),
}

public enum class SeriesIdentityStatus(override val wire: String) : WireValue {
	VERIFIED("verified"),
	UNVERIFIED("unverified"),
	AMBIGUOUS("ambiguous"),
	MISSING("missing"),
}

public enum class StructuralStatus(override val wire: String) : WireValue {
	VALID("valid"),
	INVALID("invalid"),
}
