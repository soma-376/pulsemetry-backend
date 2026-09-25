package com.team376.pulsemetry.dashboard.authentication

import com.team376.pulsemetry.dashboard.error.ErrorCode
import com.team376.pulsemetry.dashboard.error.ErrorResponseWriter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 포트로 주체를 얻어 보안 문맥에 싣는다.
 *
 * **주체가 없으면 아무것도 하지 않고 넘긴다.** 익명 인증이 꺼진 체인이라 인가 필터가 인증 없음으로 보고
 * 진입점이 401 을 쓴다 — 거부 응답을 쓰는 곳이 한 군데다.
 *
 * **포트의 예외는 401 이 아니라 503 이다** (ADR 0022 §3). 원인은 로그에만 남기고 본문에는 싣지 않는다.
 * 예외를 컨테이너까지 흘리면 `/error` 가 기본 닫힘 체인에서 다른 모양의 응답으로 바뀐다.
 *
 * **빈으로 노출하지 않는다.** Boot 은 등록되지 않은 `Filter` 빈을 모든 경로에 자동 등록한다 — 체인을 만드는
 * 메서드 안에서 생성한다(`SecurityConfig`).
 */
class DashboardAuthenticationFilter(
	private val authenticator: DashboardAuthenticator,
	private val errors: ErrorResponseWriter,
) : OncePerRequestFilter() {

	private val log = LoggerFactory.getLogger(DashboardAuthenticationFilter::class.java)

	override fun doFilterInternal(
		request: HttpServletRequest,
		response: HttpServletResponse,
		filterChain: FilterChain,
	) {
		val principal = try {
			authenticator.authenticate(request)
		} catch (e: Exception) {
			log.error("대시보드 인증 조회가 실패했다 — 503 으로 돌린다", e)
			return errors.write(request, response, ErrorCode.UNAVAILABLE)
		}

		if (principal != null) {
			val context = SecurityContextHolder.createEmptyContext()
			context.authentication = DashboardAuthentication(principal)
			SecurityContextHolder.setContext(context)
		}
		filterChain.doFilter(request, response)
	}
}
