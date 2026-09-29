package com.team376.pulsemetry.enrollment.auth

import com.team376.pulsemetry.security.user.UserAuthException
import org.springframework.core.annotation.Order
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@Order(-10)
@RestControllerAdvice(basePackageClasses = [UserAuthController::class])
class UserAuthExceptionHandler {
    @ExceptionHandler(UserAuthException::class)
    fun auth(e: UserAuthException): ResponseEntity<Map<String, String>> = error(e.status, e.code, e.retryAfter)
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun malformed(): ResponseEntity<Map<String, String>> = error(400, "invalid_request")
    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun methodNotAllowed(): ResponseEntity<Map<String, String>> = error(405, "method_not_allowed")
    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun mediaType(): ResponseEntity<Map<String, String>> = error(400, "invalid_request")
    @ExceptionHandler(Exception::class)
    fun unavailable(): ResponseEntity<Map<String, String>> = error(503, "auth_unavailable", 1)
    private fun error(status: Int, code: String, retry: Long? = null): ResponseEntity<Map<String, String>> {
        val builder = ResponseEntity.status(status).header("Cache-Control", "no-store")
        retry?.let { builder.header("Retry-After", it.toString()) }
        return builder.body(mapOf("error" to code, "message" to "사용자 인증 요청을 처리할 수 없습니다."))
    }
}
