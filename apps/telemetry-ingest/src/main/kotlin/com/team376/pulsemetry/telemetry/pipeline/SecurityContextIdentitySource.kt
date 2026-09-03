package com.team376.pulsemetry.telemetry.pipeline

import com.team376.pulsemetry.security.TelemetryTokenPrincipal
import com.team376.pulsemetry.telemetry.collector.IdentitySource
import com.team376.pulsemetry.telemetry.collector.StampedIdentity
import org.springframework.security.core.context.SecurityContextHolder

/**
 * 검증된 신원을 `SecurityContextHolder` 에서 꺼낸다.
 *
 * **신원 헤더를 실어 나르지 않는다** — 파이프라인이 단일 애플리케이션이 되어 홉이 사라졌으므로
 * 하위 단계는 헤더가 아니라 보안 컨텍스트에서 신원을 얻는다(허브 ADR 0005). 구 auth-proxy 가
 * 붙이던 `x-pulsemetry-*` 네 헤더가 이 클래스로 대체됐다.
 *
 * `memberId` 는 **일부러 심지 않는다.** 구 processor 도 `x-pulsemetry-member-id` 를 받고 버렸고
 * (허브 계약 §5 M2), `enriched_events` 에 그 컬럼이 없다. 심으면 저장되는 값이 현행과 달라진다.
 *
 * 인증을 세우지 않은 호출자(재처리 배치·테스트)에서는 `null` 이라 아무것도 심지 않는다.
 */
class SecurityContextIdentitySource : IdentitySource {

	override fun current(): StampedIdentity? {
		val principal = SecurityContextHolder.getContext().authentication?.principal
		if (principal !is TelemetryTokenPrincipal) return null
		return StampedIdentity(
			tenantId = principal.tenantId.toString(),
			installationId = principal.installationId.toString(),
		)
	}
}
