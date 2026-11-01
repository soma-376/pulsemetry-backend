package com.team376.pulsemetry.enrollment.management

import com.team376.pulsemetry.persistence.enrollment.management.ManagementException
import com.team376.pulsemetry.security.user.UserAuthException
import com.team376.pulsemetry.security.user.UserAuthService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.util.UUID


@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class ManagementApi

/** 관리 요청의 행위자. 토큰의 조직이 경로와 다르면 404, owner/admin 이 아니면 403 이다 (ADR 0026). 저장 연산이 DB 에서 한 번 더 확인한다. */
internal fun managementActor(auth: UserAuthService, tenant: UUID, request: HttpServletRequest): UUID {
    val header = request.getHeader("Authorization")
    if (header == null || !header.startsWith("Bearer ")) throw ManagementException("unauthenticated", 401)
    val identity = try { auth.verify(header.removePrefix("Bearer ")) } catch (_: UserAuthException) { throw ManagementException("unauthenticated", 401) }
    if (identity.tenantId != tenant) throw ManagementException("not_found", 404)
    if (identity.role !in setOf("owner", "admin")) throw ManagementException("forbidden", 403)
    return identity.memberId
}

@RestControllerAdvice(annotations = [ManagementApi::class])
@org.springframework.core.annotation.Order(-20)
class ManagementErrors {
    @ExceptionHandler(ManagementException::class)
    fun domain(error: ManagementException): ResponseEntity<*> = response(error.status, error.code, error.field, error.detail)
    @ExceptionHandler(org.springframework.dao.DataAccessException::class)
    fun database(): ResponseEntity<*> = response(503, "unavailable", null)
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException::class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException::class)
    fun malformed(): ResponseEntity<*> = response(400, "invalid_request", null)
    private fun response(status: Int, code: String, field: String?, detail: Any? = null): ResponseEntity<*> {
        val requestId = UUID.randomUUID().toString()
        val builder = ResponseEntity.status(status).header("X-Request-Id", requestId).header("Cache-Control", "no-store")
        if (status == 503) builder.header("Retry-After", "2")
        val fields = if (field == null) emptyList() else listOf(mapOf("field" to field, "code" to code))
        val body = mutableMapOf<String, Any>("error" to mapOf<String, Any>("code" to code, "message" to "관리 요청을 처리할 수 없습니다.",
            "fieldErrors" to fields), "requestId" to requestId)
        // 구조화된 사유(예: CSV 가져오기의 행별 오류)가 있을 때만 가산한다.
        detail?.let { body["details"] = it }
        return builder.body(body)
    }
}
