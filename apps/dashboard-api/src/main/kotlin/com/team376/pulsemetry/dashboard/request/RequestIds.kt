package com.team376.pulsemetry.dashboard.request

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.UUID

/**
 * 요청 ID (ADR 0022 §5). 모든 응답의 `X-Request-Id` 헤더, 오류 본문의 `requestId`, 로그의 상관 키가 같은 값이다.
 *
 * 요청에 실려 온 값은 문자 집합·길이가 맞을 때만 받는다 — 게이트웨이가 붙인 ID 로 로그를 대조할 수 있게 하되,
 * 클라이언트가 고른 임의 문자열이 로그에 섞이지 않게 한다. 어긋나면 서버가 UUID 를 만든다.
 */
object RequestIds {

	const val HEADER: String = "X-Request-Id"

	/** 로그 패턴이 읽는 MDC 키. `application.yaml` 의 `logging.pattern.correlation` 과 맞춘다. */
	const val MDC_KEY: String = "requestId"

	private val ACCEPTED = Regex("[A-Za-z0-9._-]{1,128}")
	private val ATTRIBUTE: String = RequestIds::class.java.name

	/** 요청에 ID 를 정해 붙이고 응답 헤더에 싣는다. */
	fun assign(request: HttpServletRequest, response: HttpServletResponse): String {
		val incoming = request.getHeader(HEADER)
		val id = if (incoming != null && ACCEPTED.matches(incoming)) incoming else UUID.randomUUID().toString()
		request.setAttribute(ATTRIBUTE, id)
		response.setHeader(HEADER, id)
		return id
	}

	/** 이미 정한 ID. 필터를 거치지 않은 경로(내부 오류 디스패치 등)에서는 지금 정한다. */
	fun of(request: HttpServletRequest, response: HttpServletResponse): String =
		request.getAttribute(ATTRIBUTE) as? String ?: assign(request, response)
}
