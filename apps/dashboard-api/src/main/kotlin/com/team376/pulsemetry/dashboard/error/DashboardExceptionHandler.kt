package com.team376.pulsemetry.dashboard.error

import com.team376.pulsemetry.dashboard.snapshot.SnapshotUnavailableException
import com.team376.pulsemetry.dashboard.store.StoreQueryRejectedException
import com.team376.pulsemetry.dashboard.store.StoreUnavailableException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.RecoverableDataAccessException
import org.springframework.dao.TransientDataAccessException
import org.springframework.http.ResponseEntity
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * 컨트롤러 계층의 예외를 오류 본문(ADR 0022 §5)으로 바꾼다.
 *
 * Spring 기본 응답(ProblemDetail · Boot 의 `/error` 본문)은 이 앱의 오류 모양과 다르다 — 여기서 통일한다.
 * **예외 메시지를 응답에 싣지 않는다.** 분류되지 않은 예외는 원인을 로그에만 남기고 500 이다.
 *
 * | 예외 | 응답 |
 * |---|---|
 * | [DashboardException] | 그 코드(400·403·404·409 …) |
 * | ClickHouse 일시 장애·상한 초과([StoreUnavailableException]), RDS 연결·일시 장애 | 503 `unavailable` + `Retry-After` |
 * | snapshot 을 지금 만들 수 없음([SnapshotUnavailableException] — 동시 build 한도·공개 CAS 거부·복사 검증 실패) | 503 `unavailable` + `Retry-After` |
 * | ClickHouse 의 문장 거부([StoreQueryRejectedException]) | 500 `internal_error` — 이 앱의 문장 결함 |
 * | 매핑 없는 경로 / 메서드 | 404 / 405 |
 * | 그 밖 | 500 `internal_error` |
 */
@RestControllerAdvice
class DashboardExceptionHandler(
	private val errors: ErrorResponseWriter,
) {

	private val log = LoggerFactory.getLogger(DashboardExceptionHandler::class.java)

	@ExceptionHandler(DashboardException::class)
	fun handleDashboard(
		exception: DashboardException,
		request: HttpServletRequest,
		response: HttpServletResponse,
	): ResponseEntity<ErrorResponse> = errors.entity(request, response, exception.code, exception.fieldErrors)

	/** 원인 로그는 연결이 이미 남겼다. */
	@ExceptionHandler(StoreUnavailableException::class)
	fun handleStoreUnavailable(request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<ErrorResponse> =
		errors.entity(request, response, ErrorCode.UNAVAILABLE)

	/** 생성 중·실패의 HTTP 표현은 프론트와 합의 전이다 — 합의 전 기본값은 503 + `Retry-After`. 원인 로그는 snapshot 쪽이 남겼다. */
	@ExceptionHandler(SnapshotUnavailableException::class)
	fun handleSnapshotUnavailable(request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<ErrorResponse> =
		errors.entity(request, response, ErrorCode.UNAVAILABLE)

	@ExceptionHandler(StoreQueryRejectedException::class)
	fun handleStoreRejected(request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<ErrorResponse> =
		errors.entity(request, response, ErrorCode.INTERNAL_ERROR)

	/** RDS 연결 실패·일시 장애. 커넥션 획득 제한 시간 초과도 여기로 온다. */
	@ExceptionHandler(
		DataAccessResourceFailureException::class,
		TransientDataAccessException::class,
		RecoverableDataAccessException::class,
	)
	fun handleRdsUnavailable(
		exception: Exception,
		request: HttpServletRequest,
		response: HttpServletResponse,
	): ResponseEntity<ErrorResponse> {
		log.error("RDS 원천 읽기 일시 장애 — 503 으로 돌린다", exception)
		return errors.entity(request, response, ErrorCode.UNAVAILABLE)
	}

	/** 인증을 통과했지만 매핑이 없는 경로. 정적 자원 서빙을 껐으므로 둘 중 하나로 온다. */
	@ExceptionHandler(NoHandlerFoundException::class, NoResourceFoundException::class)
	fun handleNotFound(request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<ErrorResponse> =
		errors.entity(request, response, ErrorCode.NOT_FOUND)

	@ExceptionHandler(HttpRequestMethodNotSupportedException::class)
	fun handleMethodNotAllowed(
		request: HttpServletRequest,
		response: HttpServletResponse,
	): ResponseEntity<ErrorResponse> = errors.entity(request, response, ErrorCode.METHOD_NOT_ALLOWED)

	@ExceptionHandler(Exception::class)
	fun handleUnexpected(
		exception: Exception,
		request: HttpServletRequest,
		response: HttpServletResponse,
	): ResponseEntity<ErrorResponse> {
		log.error("분류되지 않은 예외 — 500 으로 돌린다", exception)
		return errors.entity(request, response, ErrorCode.INTERNAL_ERROR)
	}
}
