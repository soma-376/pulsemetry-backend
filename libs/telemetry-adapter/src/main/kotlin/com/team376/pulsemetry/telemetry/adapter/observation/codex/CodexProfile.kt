package com.team376.pulsemetry.telemetry.adapter.observation.codex

import com.team376.pulsemetry.telemetry.adapter.observation.Product
import com.team376.pulsemetry.telemetry.adapter.observation.Surface
import com.team376.pulsemetry.telemetry.adapter.observation.profile.LogProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.MetricProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.ProductProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.SpanProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.VersionSet

/**
 * Codex producer 프로파일(ADR 0020 부록 A.2·A.3). 근거 기록은 테스트 리소스의 `otlp-v2/codex/PROFILE-EVIDENCE.md` 다.
 *
 * 소스로 확인한 네 버전만 적용한다 — 그 네 버전은 해당 코드가 같고 소스 위치의 행 번호만 다르다.
 * 표면은 `codex-app-server`·`codex_cli_rs` 두 곳이다. `Codex Desktop`의 확인된 버전 완료 로그는
 * [CodexDesktopCompletedProfile]이 별도로 제한하며 나머지는 generic 경로로 간다(ADR 0064).
 *
 * 로그는 [CodexLogs], 스팬 허용 목록은 [CodexSpans], 메트릭 family 는 [CodexMetrics] 가 정한다.
 *
 * 토큰 의미는 [CodexTokenSemantics]가 정확한 완료 emitter에만 적용한다(ADR 0061).
 * provider는 다른 행에서 옮겨 채우지 않으며, 확인하지 않은 emitter·진단 관측의 의미는 unknown이다.
 */
public object CodexProfile : ProductProfile {

	/** 소스로 확인한 producer 버전. */
	public val VERIFIED_VERSIONS: List<String> = listOf("0.153.4", "0.154.0-alpha.6.2", "0.155.0-alpha.2.6", "0.155.0-alpha.9.2")

	override val product: Product = Product.CODEX
	override val versions: VersionSet = VersionSet.exactly(*VERIFIED_VERSIONS.toTypedArray())
	override val surfaces: Set<Surface> = setOf(Surface.APP_SERVER, Surface.CLI)
	override val mappingVersion: String = "codex-v2"
	override val logs: LogProfile = CodexLogs
	override val spans: SpanProfile = CodexSpans
	override val metrics: MetricProfile = CodexMetrics
}
