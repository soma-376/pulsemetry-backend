package com.team376.pulsemetry.dashboard.error

/**
 * non-2xx 응답 본문 (ADR 0022 §5).
 *
 * ```json
 * {"error": {"code": "invalid_request", "message": "…", "fieldErrors": [{"field": "endDate", "code": "…"}]}, "requestId": "…"}
 * ```
 *
 * `:apps:enrollment-api` 의 `{"error", "message"}` 와 다른 모양이다 — 그쪽은 CLI 와의 계약이다.
 * [requestId] 는 응답 헤더 `X-Request-Id` 와 같은 값이다.
 */
data class ErrorResponse(
	val error: Error,
	val requestId: String,
) {
	data class Error(
		val code: String,
		val message: String,
		/** 해당 없으면 빈 배열이다. `null` 로 내지 않는다. */
		val fieldErrors: List<FieldError>,
	)

	data class FieldError(
		val field: String,
		val code: String,
	)
}
