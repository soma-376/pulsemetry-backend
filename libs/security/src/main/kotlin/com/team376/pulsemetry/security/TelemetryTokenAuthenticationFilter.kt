package com.team376.pulsemetry.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.web.filter.OncePerRequestFilter

/**
 * `Authorization: Bearer <ptt_…>` 를 읽어 인증한다.
 *
 * **이 필터는 통과시키는 법이 없다.** 헤더가 없어도 401 이다 — 인증이 파이프라인의 가장 앞이고,
 * 통과한 요청만 수집 단계에 닿아야 하기 때문이다 (허브 ADR 0005). 어느 경로에 걸지는 앱이
 * `securityMatcher` 로 정한다.
 *
 * **`ptt_` 접두사를 검사하지 않는다.** 이식 원본도 하지 않는다 — `pit_` 든 쓰레기든 해시 조회에서
 * `token_unknown` 으로 떨어진다. 접두사 검사를 넣으면 거부 사유가 갈라져 원본과 동작이 달라진다.
 *
 * **스테레오타입을 달지 않는다** (ADR 0011).
 */
class TelemetryTokenAuthenticationFilter(
	private val authenticationManager: AuthenticationManager,
	private val entryPoint: AuthenticationEntryPoint,
) : OncePerRequestFilter() {

	override fun doFilterInternal(
		request: HttpServletRequest,
		response: HttpServletResponse,
		filterChain: FilterChain,
	) {
		val token = try {
			bearerToken(request.getHeader(HttpHeaders.AUTHORIZATION))
		} catch (e: TelemetryTokenAuthenticationException) {
			return fail(request, response, e)
		}

		try {
			val authenticated = authenticationManager.authenticate(
				TelemetryTokenAuthenticationToken.unauthenticated(token),
			)
			val context = SecurityContextHolder.createEmptyContext()
			context.authentication = authenticated
			SecurityContextHolder.setContext(context)
		} catch (e: AuthenticationException) {
			return fail(request, response, e)
		}

		filterChain.doFilter(request, response)
	}

	/**
	 * 원본의 `/^Bearer\s+([^\s]+)$/i` 를 그대로 옮긴다 — 스킴은 대소문자를 가리지 않고,
	 * 사이 공백은 여러 개여도 되며, 양끝이 앵커라 토큰에 공백이 섞이면 형식 불일치다.
	 *
	 * 헤더가 **없는 것과 빈 문자열은 같게** 다룬다 (원본의 `if (!authorization)`).
	 */
	private fun bearerToken(header: String?): String {
		if (header.isNullOrEmpty()) {
			throw TelemetryTokenAuthenticationException(TelemetryTokenRejectionReason.MISSING_BEARER)
		}
		return BEARER.matchEntire(header)?.groupValues?.get(1)
			?: throw TelemetryTokenAuthenticationException(TelemetryTokenRejectionReason.MALFORMED_BEARER)
	}

	private fun fail(
		request: HttpServletRequest,
		response: HttpServletResponse,
		e: AuthenticationException,
	) {
		SecurityContextHolder.clearContext()
		entryPoint.commence(request, response, e)
	}

	private companion object {
		val BEARER = Regex("""Bearer\s+(\S+)""", RegexOption.IGNORE_CASE)
	}
}
