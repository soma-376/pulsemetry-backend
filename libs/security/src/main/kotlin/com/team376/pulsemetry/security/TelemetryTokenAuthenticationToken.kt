package com.team376.pulsemetry.security

import org.springframework.security.authentication.AbstractAuthenticationToken

/**
 * `ptt_` 자격증명의 [org.springframework.security.core.Authentication] 표현.
 *
 * 미인증 상태는 원문 토큰을 credentials 로 들고 있고, 인증된 상태는 [TelemetryTokenPrincipal] 을
 * principal 로 들고 credentials 를 버린다 — 인증이 끝난 뒤 원문을 보관할 이유가 없다.
 */
class TelemetryTokenAuthenticationToken private constructor(
	private val principal: Any?,
	private var credentials: String?,
	authenticated: Boolean,
) : AbstractAuthenticationToken(emptyList()) {

	init {
		super.setAuthenticated(authenticated)
	}

	override fun getPrincipal(): Any? = principal

	override fun getCredentials(): Any? = credentials

	override fun eraseCredentials() {
		credentials = null
		super.eraseCredentials()
	}

	/**
	 * 인증됨으로 직접 승격하지 못하게 막는다. 승격은 [TelemetryTokenAuthenticationProvider] 만 한다.
	 */
	override fun setAuthenticated(authenticated: Boolean) {
		require(!authenticated) {
			"인증 상태로 직접 바꿀 수 없다. authenticated() 로 새 인스턴스를 만들어라."
		}
		super.setAuthenticated(false)
	}

	companion object {

		fun unauthenticated(token: String): TelemetryTokenAuthenticationToken =
			TelemetryTokenAuthenticationToken(principal = null, credentials = token, authenticated = false)

		fun authenticated(principal: TelemetryTokenPrincipal): TelemetryTokenAuthenticationToken =
			TelemetryTokenAuthenticationToken(principal = principal, credentials = null, authenticated = true)
	}
}
