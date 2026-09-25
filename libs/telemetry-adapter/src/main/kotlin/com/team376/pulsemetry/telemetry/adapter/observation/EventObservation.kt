package com.team376.pulsemetry.telemetry.adapter.observation

import java.math.BigDecimal

/**
 * `telemetry_events` 한 행의 정규화 몫 — 봉투([envelope]) + 이벤트 고유 58컬럼(ADR 0020 §1·§4, 부록 D.1).
 *
 * 로그 하나 또는 허용 목록 안의 스팬 하나가 관측 하나다. 속성 순서는 DDL 의 컬럼 순서를 따른다.
 * 기본값은 "이 관측에 해당하지 않음"이다 — 측정값은 null, 분류는 `none`(없는 어휘는 `unknown`).
 *
 * 토큰·길이·duration 은 음수가 아니다. 측정 0 과 미보고 null 은 다르다(ADR 0020 §4).
 */
public data class EventObservation(
	public val envelope: ObservationEnvelope,
	public val eventType: EventType,
	public val operation: Operation = Operation.NONE,
	public val endTime: EpochNanos? = null,
	public val durationNs: Long? = null,
	public val ttftNs: Long? = null,
	public val ttftScope: TtftScope = TtftScope.NONE,
	public val turnId: String? = null,
	public val turnIdNamespace: String? = null,
	public val requestId: String? = null,
	public val requestIdNamespace: String? = null,
	public val clientRequestId: String? = null,
	public val clientRequestIdNamespace: String? = null,
	public val responseId: String? = null,
	public val responseIdNamespace: String? = null,
	public val callId: String? = null,
	public val callIdNamespace: String? = null,
	public val toolResultSeq: Long? = null,
	public val traceId: String? = null,
	public val spanId: String? = null,
	public val parentSpanId: String? = null,
	public val spanKind: SpanKind = SpanKind.NONE,
	public val spanStatusCode: SpanStatusCode = SpanStatusCode.NONE,
	public val severityNumber: Int? = null,
	public val tokensInput: Long? = null,
	public val tokensOutput: Long? = null,
	public val tokensCacheRead: Long? = null,
	public val tokensCacheCreate: Long? = null,
	public val tokensReasoning: Long? = null,
	public val tokensTool: Long? = null,
	public val tokensTotalReported: Long? = null,
	public val tokensTotalDerived: Long? = null,
	public val tokensInputUncached: Long? = null,
	public val inputSemantics: InputSemantics = InputSemantics.UNKNOWN,
	public val outputSemantics: OutputSemantics = OutputSemantics.UNKNOWN,
	public val semanticsProfile: String? = null,
	public val costReportedUsd: BigDecimal? = null,
	public val costEstimatedUsd: BigDecimal? = null,
	public val reportedCostBasis: ReportedCostBasis = ReportedCostBasis.UNKNOWN,
	public val pricingVersion: String? = null,
	public val success: Boolean? = null,
	public val httpStatus: Int? = null,
	public val errorType: ErrorType = ErrorType.NONE,
	public val attempt: Long? = null,
	public val toolName: String? = null,
	public val toolNamespace: String? = null,
	public val toolOrigin: ToolOrigin = ToolOrigin.NONE,
	public val toolAction: ToolAction = ToolAction.NONE,
	public val mcpServer: String? = null,
	public val decision: Decision = Decision.NONE,
	public val decisionRaw: String? = null,
	public val decisionSource: DecisionSource = DecisionSource.NONE,
	public val decisionScope: DecisionScope = DecisionScope.NONE,
	public val promptLength: Long? = null,
	public val responseLength: Long? = null,
	public val commandName: String? = null,
	public val stopReason: StopReason = StopReason.NONE,
	public val reasoningEffort: ReasoningEffort = ReasoningEffort.NONE,
	public val agentId: String? = null,
) {
	init {
		require(envelope.signal != ObservationSignal.METRIC) { "metric point 는 MetricPointObservation 이다" }
		for ((column, value) in listOf(
			"duration_ns" to durationNs,
			"ttft_ns" to ttftNs,
			"tokens_input" to tokensInput,
			"tokens_output" to tokensOutput,
			"tokens_cache_read" to tokensCacheRead,
			"tokens_cache_create" to tokensCacheCreate,
			"tokens_reasoning" to tokensReasoning,
			"tokens_tool" to tokensTool,
			"tokens_total_reported" to tokensTotalReported,
			"tokens_total_derived" to tokensTotalDerived,
			"tokens_input_uncached" to tokensInputUncached,
			"prompt_length" to promptLength,
			"response_length" to responseLength,
		)) {
			require(value == null || value >= 0) { "$column 은 음수가 아니다: $value" }
		}
		require(severityNumber == null || severityNumber in 0..UINT8_MAX) { "severity_number 는 UInt8 이다: $severityNumber" }
		require(httpStatus == null || httpStatus in 0..UINT16_MAX) { "http_status 는 UInt16 이다: $httpStatus" }
		// 두 금액은 서로 다른 출처다 — 음수 금액은 어느 쪽에도 유효하지 않다.
		require(costReportedUsd == null || costReportedUsd.signum() >= 0) { "cost_reported_usd 가 음수다" }
		require(costEstimatedUsd == null || costEstimatedUsd.signum() >= 0) { "cost_estimated_usd 가 음수다" }
		require((costEstimatedUsd == null) == (pricingVersion == null)) { "추정 비용과 pricing_version 은 함께 있다(ADR 0020 §4)" }
	}
}

internal const val UINT8_MAX: Int = 255
