package com.team376.pulsemetry.enrollment.auth

import com.team376.pulsemetry.enrollment.support.EnrollmentTestData
import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/**
 * 인증 오류의 429·503이 명세 §11대로 `application/json;charset=UTF-8`과 한국어 `message`를 보존하는지 실제 HTTP로 본다.
 * 컨트롤러 앞의 필터가 쓰는 오류(진입 요청 제한 429, 제한 상태를 읽지 못한 503)와 컨트롤러가 쓰는 오류(재조회의 429)를 둘 다 본다.
 * 본문은 응답 헤더의 문자셋과 무관하게 UTF-8 바이트로 읽는다 — 문자셋을 정하지 않은 서블릿 응답은 한국어를 `?`로 바꿔 보낸다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["pulsemetry.user-auth.enabled=true",
        "pulsemetry.user-auth.allowed-origins=http://localhost:3000"])
@Import(PostgresContainerConfig::class, EnrollmentTestData::class, AuthClockConfig::class)
class AuthFilterErrorResponseTest : AbstractUserAuthApiTest() {
    /** 명세 §11: 모든 인증 오류의 `message`. */
    private val specMessage = "사용자 인증 요청을 처리할 수 없습니다."

    private fun manifest(rt: String): HttpResponse<ByteArray> = http.send(HttpRequest.newBuilder(URI("http://localhost:$port/v1/manifest"))
        .header("Authorization", "Bearer $rt").GET().build(), HttpResponse.BodyHandlers.ofByteArray())

    private fun loginBytes(email: String = this.email): HttpResponse<ByteArray> = http.send(HttpRequest.newBuilder(URI("http://localhost:$port/v1/auth/login"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(mapOf("tenant_id" to tenant, "email" to email, "password" to password))))
        .build(), HttpResponse.BodyHandlers.ofByteArray())

    private fun assertAuthError(response: HttpResponse<ByteArray>, status: Int, code: String, retryAfter: Boolean = true) {
        val text = String(response.body(), StandardCharsets.UTF_8)
        assertThat(response.statusCode()).withFailMessage(text).isEqualTo(status)
        val type = MediaType.parseMediaType(response.headers().firstValue("Content-Type").orElseThrow())
        assertThat(type.isCompatibleWith(MediaType.APPLICATION_JSON)).describedAs(type.toString()).isTrue()
        assertThat(type.charset).describedAs(type.toString()).isEqualTo(StandardCharsets.UTF_8)
        val body = mapper.readTree(text)
        assertThat(body.propertyNames().toList()).containsExactlyInAnyOrder("error", "message")
        assertThat(body.path("error").asString()).isEqualTo(code)
        assertThat(body.path("message").asString()).isEqualTo(specMessage)
        if (retryAfter) assertThat(response.headers().firstValue("Retry-After").orElse("")).matches("[1-9][0-9]*")
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store")
    }

    @Test fun `재조회의 429는 UTF-8 JSON이고 한국어 문장과 rate_limited와 Retry-After를 그대로 준다`() {
        // 형식이 틀린 RT 는 진입(IP) 버킷으로 센다(ADR 0052). 판정은 재조회 컨트롤러가 한다.
        val malformed = "urt_not-a-token"
        repeat(30) {
            assertAuthError(manifest(malformed), 401, "invalid_credentials", retryAfter = false)
        }
        val limited = manifest(malformed)
        assertAuthError(limited, 429, "rate_limited")
        // 시계가 멈춰 있어 창(60초)이 통째로 남는다.
        assertThat(limited.headers().firstValue("Retry-After")).hasValue("60")
    }

    @Test fun `필터가 쓰는 진입 요청 제한의 429도 UTF-8 JSON이다`() {
        repeat(30) { assertThat(loginBytes("nobody-$it@example.test").statusCode()).isEqualTo(401) }
        val limited = loginBytes("nobody-30@example.test")
        assertAuthError(limited, 429, "rate_limited")
        assertThat(limited.headers().firstValue("Retry-After")).hasValue("60")
    }

    @Test fun `제한 상태를 읽지 못한 503도 UTF-8 JSON이고 auth_unavailable과 Retry-After를 주며 예외 원문을 싣지 않는다`() {
        sql("ALTER TABLE enrollment.auth_attempts RENAME TO auth_attempts_unavailable")
        try {
            val response = loginBytes()
            assertAuthError(response, 503, "auth_unavailable")
            assertThat(response.headers().firstValue("Retry-After")).hasValue("1")
            assertThat(String(response.body(), StandardCharsets.UTF_8)).doesNotContain("auth_attempts", "relation", "SQL", password)
        } finally { sql("ALTER TABLE enrollment.auth_attempts_unavailable RENAME TO auth_attempts") }
    }
}
