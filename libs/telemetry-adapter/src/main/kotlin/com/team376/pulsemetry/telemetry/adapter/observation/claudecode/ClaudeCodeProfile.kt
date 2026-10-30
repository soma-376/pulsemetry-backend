package com.team376.pulsemetry.telemetry.adapter.observation.claudecode

import com.team376.pulsemetry.telemetry.adapter.observation.Product
import com.team376.pulsemetry.telemetry.adapter.observation.profile.LogProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.MetricProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.ProductProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.SpanProfile

/**
 * Claude Code producer 프로파일(ADR 0020 부록 A.2). 근거 기록은 테스트 리소스의 `otlp-v2/claude_code/PROFILE-EVIDENCE.md` 다.
 *
 * ADR 0074에 따라 producer 버전은 진단 정보이며 알려진 이벤트 이름·필드 계약으로 매핑한다. 표면은
 * 근거가 없어 `unknown` 그대로다(부록 A.1). 로그는 [ClaudeCodeLogs], 스팬 허용 목록은 [ClaudeCodeSpans],
 * 메트릭 family는 [ClaudeCodeMetrics]가 정한다. 모르는 이름·필드를 자동 승격하지 않는다.
 */
public object ClaudeCodeProfile : ProductProfile {

	override val product: Product = Product.CLAUDE_CODE
	override val mappingVersion: String = "claude-code-event-contract-v1"
	override val logs: LogProfile = ClaudeCodeLogs
	override val spans: SpanProfile = ClaudeCodeSpans
	override val metrics: MetricProfile = ClaudeCodeMetrics
}
