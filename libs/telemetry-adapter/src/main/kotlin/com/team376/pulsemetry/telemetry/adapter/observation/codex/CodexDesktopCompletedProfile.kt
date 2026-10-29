package com.team376.pulsemetry.telemetry.adapter.observation.codex

import com.team376.pulsemetry.telemetry.adapter.observation.Product
import com.team376.pulsemetry.telemetry.adapter.observation.Surface
import com.team376.pulsemetry.telemetry.adapter.observation.profile.LogProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.MetricProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.ProductProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.SpanProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.VersionSet

/**
 * 확인한 Desktop 버전의 완료 emitter만 지원한다(ADR 0064·0072 · PROFILE-EVIDENCE.md §12·13).
 * 배포 바이너리 전체의 소스 대응을 주장하지 않으며 다른 이벤트·스팬·메트릭으로 확대하지 않는다.
 * 완료 로그의 필드·오류 가드는 기존 제한 프로파일과 같고, 토큰 의미는 별도로 범위를 확인한다.
 */
public object CodexDesktopCompletedProfile : ProductProfile {

	internal val supportedVersions: Set<String> = setOf("0.153.4", "0.154.0-alpha.6.2", "0.165.0")

	override val product: Product = Product.CODEX
	override val versions: VersionSet = VersionSet.exactly(*supportedVersions.toTypedArray())
	override val surfaces: Set<Surface> = setOf(Surface.DESKTOP)
	override val mappingVersion: String = "codex-desktop-completed-v2"
	override val logs: LogProfile = CodexCompletedProfile.logs
	override val spans: SpanProfile? = null
	override val metrics: MetricProfile? = null
}
