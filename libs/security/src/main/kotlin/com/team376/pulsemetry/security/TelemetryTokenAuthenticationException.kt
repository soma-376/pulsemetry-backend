package com.team376.pulsemetry.security

import org.springframework.security.core.AuthenticationException

/**
 * 거부 사유를 실어 나르는 인증 실패.
 *
 * 사유는 **로그용**이다. 응답으로 나가지 않는다 — [TelemetryTokenAuthenticationEntryPoint] 가
 * 열한 가지를 같은 본문으로 접는다.
 *
 * 메시지에 토큰 원본을 넣지 마라. 허브 계약 §4 가 토큰을 로그·에러 응답 어디에도 담지 말라고 정했다.
 */
class TelemetryTokenAuthenticationException(
	val reason: TelemetryTokenRejectionReason,
) : AuthenticationException(reason.code)
