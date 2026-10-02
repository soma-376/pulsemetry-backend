package com.team376.pulsemetry.enrollment.error

import jakarta.servlet.http.HttpServletResponse
import tools.jackson.databind.json.JsonMapper

/**
 * 서블릿 필터가 컨트롤러 앞에서 직접 쓰는 `{error, message}` 오류 응답(사용자 인증의 요청 제한·장애, 문의 접수의 요청 제한·장애).
 *
 * 문자셋을 정하지 않은 응답은 서블릿 기본값(ISO-8859-1)으로 인코딩되어 한국어 `message`가 `?`로 바뀐다.
 * 그래서 `Content-Type`에 UTF-8을 명시하고 본문을 UTF-8 바이트로 쓴다. 형태는 명세 §2.2·§11의 오류 본문과 같다.
 * 필터가 응답 writer를 먼저 꺼내지 않은 상태에서만 부른다.
 */
object FilterErrorResponse {
    const val CONTENT_TYPE = "application/json;charset=UTF-8"
    private val mapper = JsonMapper.builder().build()

    fun write(response: HttpServletResponse, status: Int, code: String, message: String, retryAfter: Long?) {
        if (response.isCommitted) return
        val body = mapper.writeValueAsBytes(linkedMapOf("error" to code, "message" to message))
        response.status = status
        response.characterEncoding = "UTF-8"
        response.contentType = CONTENT_TYPE
        retryAfter?.let { response.setHeader("Retry-After", it.toString()) }
        response.setContentLength(body.size)
        response.outputStream.write(body)
    }
}
