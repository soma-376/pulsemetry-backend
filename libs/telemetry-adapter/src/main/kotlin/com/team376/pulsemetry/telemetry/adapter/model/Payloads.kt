package com.team376.pulsemetry.telemetry.adapter.model

/**
 * 토큰 계수. **없는 값은 0 이 아니라 null 이다** — "0건"과 "측정 불가"는 다른 사실이다.
 *
 * [billable] 이 [reasoning]·[tool] 을 빼는 것이 이 클래스의 요점이다. 둘은 [output] 의
 * 부분집합일 수 있어 더하면 이중계산이 된다. 두 프로퍼티 모두 **직렬화되지 않는다** —
 * 이식 원본이 계산 프로퍼티로 두었고 golden fixture 에도 나타나지 않는다.
 */
public class Tokens(
	public val input: Int? = null,
	public val output: Int? = null,
	public val cacheRead: Int? = null,
	public val cacheCreate: Int? = null,
	public val reasoning: Int? = null,
	public val tool: Int? = null,
	public val totalReported: Int? = null,
) {
	/** 과금 대상 합. 여기에 [reasoning]·[tool] 을 더하지 마라. */
	public val billable: Int
		get() = (input ?: 0) + (output ?: 0) + (cacheRead ?: 0) + (cacheCreate ?: 0)

	/** 벤더가 보고한 총계와 [billable] 이 맞는지. 보고값이 없으면 판정하지 않는다. */
	public fun reconciles(): Boolean? = totalReported?.let { it == billable }
}

/** 모든 payload 의 공통 상위. 로그·스팬이 일부를 공유한다. */
public sealed interface Payload

/** 로그가 가질 수 있는 payload. */
public sealed interface LogPayload : Payload

/** 스팬이 가질 수 있는 payload. */
public sealed interface SpanPayload : Payload

/**
 * LLM 호출. 로그(`api_request`)와 스팬(`llm_request`)이 공유한다.
 *
 * **스팬에서는 [tokens]·[costUsd]·[source] 를 채우지 않는다.** 같은 호출을 로그가 이미
 * 싣고 둘은 [requestId] 로 조인되므로, 스팬에서 또 실으면 이중계산이다. 필드는 공유하되
 * 값만 비운다.
 */
public class LlmCall(
	public val model: String? = null,
	public val tokens: Tokens = Tokens(),
	public val costUsd: Double? = null,
	public val costSource: ValueSource = ValueSource.ESTIMATED,
	public val source: String? = null,
	/**
	 * 요청 설정의 reasoning effort. 벤더마다 값이 달라(high/low/minimal/xhigh/숫자 budget)
	 * enum 이 아니라 String 이다. 요청값에서만 읽고 토큰 사용량으로 추론하지 않는다.
	 */
	public val reasoningEffort: String? = null,
	public val durationMs: Int? = null,
	public val ttftMs: Int? = null,
	public val stopReason: String? = null,
	public val attempt: Int? = null,
	public val requestId: String? = null,
	public val errorType: String? = null,
	public val statusCode: Int? = null,
) : LogPayload, SpanPayload

/** 모델 응답. 호출 수를 부풀리지 않으려고 [LlmCall] 과 다른 타입으로 둔다. */
public class LlmResponse(
	public val model: String? = null,
	public val responseLength: Int? = null,
	public val source: String? = null,
	public val requestId: String? = null,
	public val stopReason: String? = null,
	public val refusalCategory: String? = null,
) : LogPayload

/** 사용자 프롬프트. **원문은 담지 않는다** — 길이와 커맨드 이름뿐이다. */
public class Prompt(
	public val length: Int? = null,
	public val commandName: String? = null,
) : LogPayload

/** 툴 호출. */
public class ToolCall(
	public val toolName: String? = null,
	public val toolKind: ToolKind = ToolKind.UNKNOWN,
	public val action: ToolAction = ToolAction.OTHER,
	public val files: List<String> = emptyList(),
	public val command: String? = null,
	public val success: Boolean? = null,
	public val errorType: String? = null,
	public val durationMs: Int? = null,
	public val mcpServer: String? = null,
	public val agentId: String? = null,
	public val parentAgentId: String? = null,
) : LogPayload, SpanPayload

/** 툴 실행 승인 결정. */
public class ToolDecision(
	public val decision: Decision = Decision.UNKNOWN,
	public val decidedBy: DecisionSource = DecisionSource.UNKNOWN,
	public val scope: DecisionScope = DecisionScope.UNKNOWN,
	public val blockedOnUserMs: Int? = null,
	public val toolName: String? = null,
) : LogPayload, SpanPayload

/**
 * 세션·턴·훅 같은 생애주기 사건.
 *
 * [attrs] 값이 전부 String 인 것이 계약이다. 승격 대상은 어댑터가 화이트리스트로
 * 열거하며, 속성 전체를 통째로 복사하지 않는다.
 */
public class Lifecycle(
	public val kind: String,
	public val startType: String? = null,
	public val activeTimeSec: Int? = null,
	public val turnCount: Int? = null,
	public val tokensBefore: Int? = null,
	public val tokensAfter: Int? = null,
	public val attrs: Map<String, String> = emptyMap(),
) : LogPayload, SpanPayload
