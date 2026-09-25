package com.team376.pulsemetry.dashboard.request

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 모든 요청에 [RequestIds] 를 붙인다. **보안 필터 체인보다 앞에서 돈다**(`WebConfig`) — 401·404 거부 응답에도
 * 헤더가 있어야 한다.
 */
class RequestIdFilter : OncePerRequestFilter() {

	override fun doFilterInternal(
		request: HttpServletRequest,
		response: HttpServletResponse,
		filterChain: FilterChain,
	) {
		MDC.put(RequestIds.MDC_KEY, RequestIds.assign(request, response))
		try {
			filterChain.doFilter(request, response)
		} finally {
			MDC.remove(RequestIds.MDC_KEY)
		}
	}
}
