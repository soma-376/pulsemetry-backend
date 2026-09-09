package com.team376.pulsemetry.security

import java.util.UUID

/**
 * `ptt_` 검증을 통과한 요청의 신원.
 *
 * **신원은 전적으로 토큰에서 파생된다.** 클라이언트가 자칭한 값을 신뢰하지 않는다는 것이
 * OTLP 계약의 중심 결정이다 (허브 `contracts/telemetry-ingest.md` §3).
 *
 * 이식 원본(auth-proxy)은 이 네 값을 `x-pulsemetry-*` 헤더 4종으로 하위에 실어 보냈다.
 * **그 전파는 허브 ADR 0005 가 폐기했다** — 파이프라인이 단일 애플리케이션이 되어 홉이 사라졌으므로,
 * 하위 단계는 헤더가 아니라 `SecurityContextHolder` 에서 신원을 얻는다.
 */
data class TelemetryTokenPrincipal(
	val tokenId: UUID,
	val tenantId: UUID,
	val installationId: UUID,
	val memberId: UUID,
)
