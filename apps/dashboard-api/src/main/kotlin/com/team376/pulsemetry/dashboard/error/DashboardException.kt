package com.team376.pulsemetry.dashboard.error

/**
 * 오류 본문(ADR 0022 §5)으로 끝나는 예외. 메시지를 싣지 않는다 — 응답 문장은 [ErrorCode.message] 다.
 */
class DashboardException(
	val code: ErrorCode,
	val fieldErrors: List<ErrorResponse.FieldError> = emptyList(),
) : RuntimeException(code.code) {

	companion object {
		fun invalid(field: String, code: FieldErrorCode) =
			DashboardException(ErrorCode.INVALID_REQUEST, listOf(ErrorResponse.FieldError(field, code.wire)))
	}
}
