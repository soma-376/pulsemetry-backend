package com.team376.pulsemetry.dashboard.authentication

import jakarta.servlet.http.HttpServletRequest

/**
 * 대시보드 요청의 인증 포트 (ADR 0022 §3).
 *
 * 요청에서 주체를 얻으면 돌려주고, 인증할 수 없으면 `null` 이다 — 헤더가 없든 자격이 틀렸든 같다.
 * **저장소 장애는 `null` 이 아니라 예외로 올린다.** 필터가 그것을 401 이 아니라 503 으로 바꾼다.
 *
 * 기본 런타임 구현은 [RejectingDashboardAuthenticator] 하나다. 사용자 인증(ADR 0007)이 서면 그 검증을 잇는
 * 어댑터가 이 인터페이스를 구현한다. 테스트 전용 구현은 테스트 소스에만 둔다.
 */
fun interface DashboardAuthenticator {

	fun authenticate(request: HttpServletRequest): DashboardPrincipal?
}
