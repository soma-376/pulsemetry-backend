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
 * 표면은 `codex-app-server`·`codex_cli_rs` 두 곳이다. `Codex Desktop` 은 배포 빌드의 소스로 확인하지 못해 generic
 * 경로로 간다(ADR 0020 부록 C).
 *
 * 토큰 의미 프로파일(`semantics_profile`)은 아직 없다 — 사용량 관측의 파생 토큰은 null 이다.
 */
public object CodexProfile : ProductProfile {

	/** 소스로 확인한 producer 버전. */
	public val VERIFIED_VERSIONS: List<String> = listOf("0.153.4", "0.154.0-alpha.6.2", "0.155.0-alpha.2.6", "0.155.0-alpha.9.2")

	override val product: Product = Product.CODEX
	override val versions: VersionSet = VersionSet.exactly(*VERIFIED_VERSIONS.toTypedArray())
	override val surfaces: Set<Surface> = setOf(Surface.APP_SERVER, Surface.CLI)
	override val mappingVersion: String = "codex-v1"
	override val logs: LogProfile = CodexLogs
	override val spans: SpanProfile? = null
	override val metrics: MetricProfile? = null
}
