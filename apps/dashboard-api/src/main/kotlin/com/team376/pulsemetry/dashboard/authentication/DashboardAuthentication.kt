package com.team376.pulsemetry.dashboard.authentication

import org.springframework.security.authentication.AbstractAuthenticationToken

/**
 * 포트가 돌려준 주체를 Spring Security 문맥에 싣는 인증 객체. 만들어지는 순간 인증된 상태다 —
 * 검증은 포트가 이미 했다.
 *
 * 권한(`GrantedAuthority`)을 싣지 않는다. 인가는 주체의 역할로 서비스가 판단한다(ADR 0022 §3).
 */
class DashboardAuthentication(
	private val principal: DashboardPrincipal,
) : AbstractAuthenticationToken(emptyList()) {

	init {
		isAuthenticated = true
	}

	override fun getPrincipal(): DashboardPrincipal = principal

	override fun getCredentials(): Any? = null
}
