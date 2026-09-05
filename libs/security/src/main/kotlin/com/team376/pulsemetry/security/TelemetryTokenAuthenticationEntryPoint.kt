package com.team376.pulsemetry.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import java.nio.charset.StandardCharsets

/**
 * 거부 사유 열한 가지를 **하나의 401** 로 접는다.
 *
 * 사유를 알려 주면 유효한 토큰을 찾는 탐색이 쉬워진다. 원본도 같은 이유로 사유를 응답에 담지 않고,
 * `WWW-Authenticate` 헤더도 붙이지 않는다.
 *
 * 본문은 상수다. 직렬화기를 쓰지 않는 것은 의도다 — 이 라이브러리가 앱의 JSON 설정에 딸려 들어가면
 * (`fail-on-unknown-properties`, 날짜 표기 …) 그 설정이 바뀔 때 계약이 같이 흔들린다.
 */
class TelemetryTokenAuthenticationEntryPoint : AuthenticationEntryPoint {

	override fun commence(
		request: HttpServletRequest,
		response: HttpServletResponse,
		authException: AuthenticationException,
	) {
		response.status = HttpStatus.UNAUTHORIZED.value()
		response.contentType = MediaType.APPLICATION_JSON_VALUE
		response.characterEncoding = StandardCharsets.UTF_8.name()
		response.writer.write(BODY)
	}

	companion object {
		/** 이식 원본의 `AppError(401, "Invalid or expired credential", "unauthorized")` 와 같은 바이트다. */
		const val BODY: String = """{"error":"unauthorized","message":"Invalid or expired credential"}"""
	}
}
