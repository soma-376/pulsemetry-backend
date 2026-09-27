package com.team376.pulsemetry.dashboard.error

import com.team376.pulsemetry.dashboard.config.DashboardApiProperties
import com.team376.pulsemetry.dashboard.request.RequestIds
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * 오류 응답을 만드는 한 곳. 보안 필터의 거부(서블릿 응답에 직접 쓴다)와 컨트롤러 예외(`ResponseEntity`)가
 * 같은 본문·헤더를 내도록 둘 다 여기를 거친다.
 *
 * 503 에는 `Retry-After` 를 싣는다(ADR 0022 §5). 값은 필수 설정이다.
 */
@Component
class ErrorResponseWriter(
	private val mapper: ObjectMapper,
	properties: DashboardApiProperties,
) {

	private val retryAfterSeconds: String = properties.retryAfter.toSeconds().toString()

	fun entity(
		request: HttpServletRequest,
		response: HttpServletResponse,
		code: ErrorCode,
		fieldErrors: List<ErrorResponse.FieldError> = emptyList(),
	): ResponseEntity<ErrorResponse> {
		val builder = ResponseEntity.status(code.status).contentType(MediaType.APPLICATION_JSON)
		retryAfter(code)?.let { builder.header(HttpHeaders.RETRY_AFTER, it) }
		return builder.body(body(request, response, code, fieldErrors))
	}

	fun write(
		request: HttpServletRequest,
		response: HttpServletResponse,
		code: ErrorCode,
		fieldErrors: List<ErrorResponse.FieldError> = emptyList(),
	) {
		val body = body(request, response, code, fieldErrors)
		response.status = code.status.value()
		response.contentType = MediaType.APPLICATION_JSON_VALUE
		retryAfter(code)?.let { response.setHeader(HttpHeaders.RETRY_AFTER, it) }
		mapper.writeValue(response.outputStream, body)
	}

	private fun body(
		request: HttpServletRequest,
		response: HttpServletResponse,
		code: ErrorCode,
		fieldErrors: List<ErrorResponse.FieldError>,
	) = ErrorResponse(
		error = ErrorResponse.Error(code.code, code.message, fieldErrors),
		requestId = RequestIds.of(request, response),
	)

	private fun retryAfter(code: ErrorCode): String? =
		if (code == ErrorCode.UNAVAILABLE) retryAfterSeconds else null
}
