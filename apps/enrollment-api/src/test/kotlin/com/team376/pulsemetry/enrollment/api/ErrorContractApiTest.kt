package com.team376.pulsemetry.enrollment.api

import com.team376.pulsemetry.support.PostgresContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/**
 * 컨트롤러에 닿지 못한 요청도 에러 계약을 지키는지 (PLAN.md §6.7).
 *
 * CLI 는 non-2xx 본문을 **그대로 사용자 터미널에 출력한다.** 그래서 404·405·415 처럼
 * Spring 이 자체 응답을 만들어 버리는 구간도 `{"error": ..., "message": ...}` 여야 한다.
 * ProblemDetail 이 새어 나가면 사용자가 우리가 쓰지 않은 문장을 보게 된다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresContainerConfig::class)
class ErrorContractApiTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var objectMapper: ObjectMapper

	private val http: HttpClient = HttpClient.newHttpClient()

	@Test
	@DisplayName("알 수 없는 경로는 404 not_found 이고 본문이 에러 계약을 지킨다")
	fun unknownPathIsNotFound() {
		val response = send("GET", "/v1/nope")

		assertThat(response.statusCode()).isEqualTo(404)
		assertThat(errorCode(response)).isEqualTo("not_found")
		assertBodyShape(response)
	}

	@Test
	@DisplayName("POST 전용 경로에 GET 을 보내면 405 method_not_allowed")
	fun wrongMethodIsMethodNotAllowed() {
		val response = send("GET", "/v1/enroll")

		assertThat(response.statusCode()).isEqualTo(405)
		assertThat(errorCode(response)).isEqualTo("method_not_allowed")
		assertBodyShape(response)
	}

	@Test
	@DisplayName("JSON 이 아닌 Content-Type 은 400 invalid_request")
	fun unsupportedMediaTypeIsInvalidRequest() {
		val response = send("POST", "/v1/enroll", body = "code=ABCD-EFGH-JKMN", contentType = "text/plain")

		assertThat(response.statusCode()).isEqualTo(400)
		assertThat(errorCode(response)).isEqualTo("invalid_request")
		assertBodyShape(response)
	}

	@Test
	@DisplayName("에러 본문에 요청 내용이 되돌아오지 않는다 (R4)")
	fun errorBodyDoesNotEchoRequest() {
		val response = send("POST", "/v1/enroll", body = "code=ABCD-EFGH-JKMN", contentType = "text/plain")

		assertThat(response.body()).doesNotContain("ABCD-EFGH-JKMN")
	}

	// ── 헬퍼 ─────────────────────────────────────────────────────────────────

	/** 본문 키가 정확히 `error`·`message` 두 개인지. ProblemDetail 은 `type`·`title` 등을 더 싣는다. */
	private fun assertBodyShape(response: HttpResponse<String>) {
		assertThat(objectMapper.readTree(response.body()).propertyNames())
			.containsExactlyInAnyOrder("error", "message")
	}

	private fun send(
		method: String,
		path: String,
		body: String? = null,
		contentType: String? = null,
	): HttpResponse<String> {
		val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
		contentType?.let { builder.header("Content-Type", it) }
		builder.method(
			method,
			body?.let { HttpRequest.BodyPublishers.ofString(it, StandardCharsets.UTF_8) }
				?: HttpRequest.BodyPublishers.noBody(),
		)
		return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
	}

	private fun errorCode(response: HttpResponse<String>): String =
		objectMapper.readTree(response.body()).get("error").asString()
}
