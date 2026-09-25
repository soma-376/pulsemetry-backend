package com.team376.pulsemetry.telemetry.adapter.observation.claudecode

import com.team376.pulsemetry.telemetry.adapter.observation.Product
import com.team376.pulsemetry.telemetry.adapter.observation.profile.LogProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.MetricProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.ProductProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.SpanProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.VersionSet

/**
 * Claude Code producer 프로파일(ADR 0020 부록 A.2). 근거 기록은 테스트 리소스의 `otlp-v2/claude_code/PROFILE-EVIDENCE.md` 다.
 *
 * producer 소스가 공개돼 있지 않아 실캡처 fixture 가 있는 버전만 적용한다 — 그 밖의 버전은 generic 경로다. 표면은
 * 근거가 없어 `unknown` 그대로다(부록 A.1). 로그는 [ClaudeCodeLogs] 가 정한다.
 */
public object ClaudeCodeProfile : ProductProfile {

	/** 실캡처 fixture 로 wire 모양을 확인한 producer 버전. */
	public val VERIFIED_VERSIONS: List<String> = listOf(
		"2.1.269", "2.1.270", "2.1.272", "2.1.273", "2.1.278", "2.1.280", "2.1.281", "2.1.282",
	)

	override val product: Product = Product.CLAUDE_CODE
	override val versions: VersionSet = VersionSet.exactly(*VERIFIED_VERSIONS.toTypedArray())
	override val mappingVersion: String = "claude-code-v1"
	override val logs: LogProfile = ClaudeCodeLogs
	override val spans: SpanProfile? = null
	override val metrics: MetricProfile? = null
}
