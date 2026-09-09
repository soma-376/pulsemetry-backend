package com.team376.pulsemetry.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

/**
 * 인증 **조회가 실패**했을 때의 응답을 정한다 — 토큰이 틀린 것이 아니라 조회 자체가 안 된 경우다.
 *
 * DB 장애를 401 로 접으면 데몬이 "토큰이 틀렸다"로 읽고 토큰을 폐기·재발급한다. 반대로 예외를
 * 컨테이너까지 흘리면 앱의 오류 경로가 무엇을 돌려줄지 이 라이브러리가 알 수 없다 — 기본 닫힘
 * 체인이 오류 경로를 막고 있으면 403 이 되어 같은 결과가 난다. 그래서 응답은 여기서 쓰되,
 * **무엇을 쓸지는 앱이 정한다**(ADR 0011). 상태 코드는 크로스레포 계약이라 라이브러리가 고르지 않는다.
 *
 * 기본값 [RETHROW] 는 예외를 그대로 올린다 — 이 seam 을 배선하지 않은 앱의 동작이 바뀌지 않는다.
 */
fun interface TelemetryTokenUnavailableHandler {

	fun handle(request: HttpServletRequest, response: HttpServletResponse, cause: RuntimeException)

	companion object {
		val RETHROW: TelemetryTokenUnavailableHandler = TelemetryTokenUnavailableHandler { _, _, cause -> throw cause }
	}
}
