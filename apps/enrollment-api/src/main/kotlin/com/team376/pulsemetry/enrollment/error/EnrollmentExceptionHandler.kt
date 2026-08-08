package com.team376.pulsemetry.enrollment.error

import com.team376.pulsemetry.enrollment.contract.ErrorResponse
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/**
 * 전역 에러 핸들러 (PLAN.md §6.7).
 *
 * 응답 본문은 `{"error": ..., "message": ...}` 두 필드뿐이다.
 * 예외 메시지를 그대로 흘리지 않는다 — Jackson 의 파싱 오류에는 요청 본문 일부가 섞여 있어서
 * 초대 코드가 로그·응답으로 새어 나갈 수 있다 (R4).
 */
@RestControllerAdvice
class EnrollmentExceptionHandler {

	@ExceptionHandler(EnrollmentException::class)
	fun handleEnrollment(exception: EnrollmentException): ResponseEntity<ErrorResponse> =
		ResponseEntity
			.status(exception.errorCode.status)
			.body(ErrorResponse(exception.errorCode.code, exception.message))

	/**
	 * 본문이 JSON 이 아니거나, 계약에 없는 필드가 섞였거나, 타입이 맞지 않을 때.
	 * `FAIL_ON_UNKNOWN_PROPERTIES=true` 라서 unknown field 도 여기로 온다.
	 */
	@ExceptionHandler(HttpMessageNotReadableException::class)
	fun handleUnreadableBody(exception: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> {
		val error = EnrollmentException.malformedBody()
		return ResponseEntity
			.status(error.errorCode.status)
			.body(ErrorResponse(error.errorCode.code, error.message))
	}

	/**
	 * 경로 변수의 타입이 안 맞을 때 (`/v1/invitations/not-a-uuid/revoke`).
	 * Spring 기본 응답은 ProblemDetail 이라 우리 에러 계약과 모양이 다르다 — 여기서 통일한다.
	 */
	@ExceptionHandler(MethodArgumentTypeMismatchException::class)
	fun handlePathTypeMismatch(
		exception: MethodArgumentTypeMismatchException,
	): ResponseEntity<ErrorResponse> {
		val error = EnrollmentException.malformedBody()
		return ResponseEntity
			.status(error.errorCode.status)
			.body(ErrorResponse(error.errorCode.code, error.message))
	}
}
