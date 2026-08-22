package com.team376.pulsemetry.enrollment.error

import org.springframework.http.HttpStatus

/**
 * PLAN.md §6.7 의 에러 계약.
 *
 * CLI 는 non-2xx 본문을 **그대로 사용자 터미널에 출력한다.** 그래서 메시지는 한국어로,
 * 사용자가 다음에 무엇을 해야 할지 알 수 있게 쓴다. "잘못된 요청" 같은 말은 도움이 되지 않는다.
 *
 * 메시지에 초대 코드나 토큰 원본을 담지 마라 (R4).
 */
enum class EnrollmentErrorCode(val code: String, val status: HttpStatus) {

	INVALID_REQUEST("invalid_request", HttpStatus.BAD_REQUEST),
	INVITATION_NOT_FOUND("invitation_not_found", HttpStatus.NOT_FOUND),
	INVITATION_USED("invitation_used", HttpStatus.CONFLICT),
	INVITATION_REVOKED("invitation_revoked", HttpStatus.CONFLICT),
	INVITATION_EXPIRED("invitation_expired", HttpStatus.GONE),
	MANIFEST_NOT_CONFIGURED("manifest_not_configured", HttpStatus.CONFLICT),
	UNAUTHORIZED("unauthorized", HttpStatus.UNAUTHORIZED),
	FORBIDDEN("forbidden", HttpStatus.FORBIDDEN),
	INSTALLATION_REVOKED("installation_revoked", HttpStatus.FORBIDDEN),
	NOT_FOUND("not_found", HttpStatus.NOT_FOUND),
	METHOD_NOT_ALLOWED("method_not_allowed", HttpStatus.METHOD_NOT_ALLOWED),
}
