package com.team376.pulsemetry.enrollment.error

import com.team376.pulsemetry.enrollment.contract.ErrorResponse
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

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
	fun handleUnreadableBody(exception: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> =
		asErrorResponse(EnrollmentException.malformedBody())

	/**
	 * 경로 변수의 타입이 안 맞을 때 (`/v1/invitations/not-a-uuid/revoke`).
	 * Spring 기본 응답은 ProblemDetail 이라 우리 에러 계약과 모양이 다르다 — 여기서 통일한다.
	 */
	@ExceptionHandler(MethodArgumentTypeMismatchException::class)
	fun handlePathTypeMismatch(
		exception: MethodArgumentTypeMismatchException,
	): ResponseEntity<ErrorResponse> = asErrorResponse(EnrollmentException.malformedBody())

	/**
	 * 어떤 핸들러에도 걸리지 않은 경로 (`GET /v1/nope`).
	 *
	 * CLI 는 non-2xx 본문을 그대로 터미널에 출력하므로 Spring 기본 응답이 나가면
	 * 사용자가 계약과 다른 모양의 본문을 보게 된다.
	 */
	@ExceptionHandler(NoResourceFoundException::class)
	fun handleNoResource(exception: NoResourceFoundException): ResponseEntity<ErrorResponse> =
		asErrorResponse(EnrollmentException.notFound())

	/** 경로는 맞지만 메서드가 다를 때 (`GET /v1/enroll`). */
	@ExceptionHandler(HttpRequestMethodNotSupportedException::class)
	fun handleMethodNotSupported(
		exception: HttpRequestMethodNotSupportedException,
	): ResponseEntity<ErrorResponse> = asErrorResponse(EnrollmentException.methodNotAllowed())

	/**
	 * `Content-Type` 이 JSON 이 아닐 때. 본문을 읽지 못한 것이므로
	 * [EnrollmentException.malformedBody] 와 같은 400 `invalid_request` 로 묶는다.
	 */
	@ExceptionHandler(HttpMediaTypeNotSupportedException::class)
	fun handleUnsupportedMediaType(
		exception: HttpMediaTypeNotSupportedException,
	): ResponseEntity<ErrorResponse> = asErrorResponse(EnrollmentException.malformedBody())

	/** 예외 메시지는 응답에 싣지 않는다 — 우리가 쓴 문장만 나간다. */
	private fun asErrorResponse(error: EnrollmentException): ResponseEntity<ErrorResponse> =
		ResponseEntity
			.status(error.errorCode.status)
			.body(ErrorResponse(error.errorCode.code, error.message))
}
