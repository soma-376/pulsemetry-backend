package com.team376.pulsemetry.dashboard.authentication

import jakarta.servlet.http.HttpServletRequest

/**
 * 기본 런타임 구현 — **모든 요청을 인증하지 않는다** (ADR 0022 §3).
 *
 * 사용자 인증이 서기 전에는 조직 경로의 모든 요청이 401 이다. 헤더에 무엇이 실려 와도 보지 않는다.
 * 주체를 지어내는 설정 스위치를 두지 않는다 — 배포 환경에서 켜지면 인증이 통째로 사라진다.
 */
class RejectingDashboardAuthenticator : DashboardAuthenticator {

	override fun authenticate(request: HttpServletRequest): DashboardPrincipal? = null
}
