package com.team376.pulsemetry.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.web.filter.OncePerRequestFilter

/**
 * `Authorization: Bearer <ptt_…>` 를 읽어 인증한다.
 *
 * **인증 없이 다음 단계로 넘기는 경우가 없다** — 헤더가 없어도 401 이다. 인증이 파이프라인의 가장
 * 앞이고, 통과한 요청만 수집 단계에 닿아야 하기 때문이다 (허브 ADR 0005). 어느 경로에 걸지는 앱이
 * `securityMatcher` 로 정한다.
 *
 * **`ptt_` 접두사를 검사하지 않는다.** 이식 원본도 하지 않는다 — `pit_` 든 쓰레기든 해시 조회에서
 * `token_unknown` 으로 떨어진다. 접두사 검사를 넣으면 거부 사유가 갈라져 원본과 동작이 달라진다.
 *
 * **응답은 사유를 가리지 않지만 로그는 가린다.** 헤더 단계의 거부 둘은 여기서, 조회 단계의 아홉은
 * [TelemetryTokenAuthenticationProvider] 가 같은 포맷으로 남긴다. 거부 사유가 실리지 않은
 * `AuthenticationException`(예: Provider 가 등록되지 않았을 때의 `ProviderNotFoundException`)은
 * 배선 실수가 "토큰이 틀렸다"로 보이지 않도록 예외 클래스를 WARN 으로 남긴다.
 *
 * **스테레오타입을 달지 않는다** (ADR 0011).
 */
class TelemetryTokenAuthenticationFilter(
	private val authenticationManager: AuthenticationManager,
	private val entryPoint: AuthenticationEntryPoint,
) : OncePerRequestFilter() {

	private val log = LoggerFactory.getLogger(TelemetryTokenAuthenticationFilter::class.java)

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
		when {
			e !is TelemetryTokenAuthenticationException ->
				log.warn("telemetry token 인증이 사유 없이 실패했다 — 배선을 의심하라: {}", e.javaClass.name)
			// 조회 단계 사유는 Provider 가 이미 남겼다. 헤더 단계 둘만 여기서 남긴다.
			e.reason in HEADER_STAGE_REASONS ->
				log.info("telemetry token 거부: reason={} tokenId=-", e.reason.code)
		}
		SecurityContextHolder.clearContext()
		entryPoint.commence(request, response, e)
	}

	private companion object {
		val BEARER = Regex("""Bearer\s+(\S+)""", RegexOption.IGNORE_CASE)
		val HEADER_STAGE_REASONS = setOf(
			TelemetryTokenRejectionReason.MISSING_BEARER,
			TelemetryTokenRejectionReason.MALFORMED_BEARER,
		)
	}
}
