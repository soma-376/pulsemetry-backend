package com.team376.pulsemetry.telemetry

import com.team376.pulsemetry.telemetry.support.AbstractIngestIntegrationTest
import com.team376.pulsemetry.telemetry.support.IngestTestData
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * 컨트롤러가 수집 모듈의 응답을 **그대로** 내보내는지 확인한다.
 *
 * 상태 매핑과 본문 바이트 자체는 `OtlpIngestHandlerTest` 가 이미 고정한다. 여기서 보는 것은
 * Spring 이 그 앞에서 끼어들지 않는가다 — `consumes` 를 걸지 않았으니 415 가 수집 모듈의
 * `text/plain` 이어야 하고, `method` 를 막지 않았으니 405 도 마찬가지여야 한다.
 */
class OtlpIngestApiTest : AbstractIngestIntegrationTest() {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var data: IngestTestData

	private val http: HttpClient = HttpClient.newHttpClient()
	private lateinit var token: String

	@BeforeEach
	fun seed() {
		data.clear()
		token = data.seed().rawToken
	}

	@AfterEach
	fun cleanUp() {
		data.clear()
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = ["/v1/logs", "/v1/traces", "/v1/metrics"])
	@DisplayName("세 경로 모두 받는다")
	fun allThreeSignalPathsRoute(path: String) {
		val response = send(path, "application/json", "{}".toByteArray())

		assertThat(response.statusCode()).isEqualTo(200)
		assertThat(response.body()).isEqualTo("""{"partialSuccess":{}}""")
	}

	@Test
	@DisplayName("protobuf 로 보내면 protobuf 로 답한다 — Spring 이 콘텐트 협상을 하지 않는다")
	fun protobufRoundTripsAsProtobuf() {
		val body = ExportTraceServiceRequest.getDefaultInstance().toByteArray()

		val response = sendBytes("/v1/traces", "application/x-protobuf", body)

		assertThat(response.statusCode()).isEqualTo(200)
		assertThat(response.headers().firstValue("Content-Type"))
			.hasValue("application/x-protobuf")
	}

	@Test
	@DisplayName("모르는 Content-Type 은 수집 모듈의 415 다 — Spring 이 먼저 거부하지 않는다")
	fun anUnknownContentTypeIsTheCollectorsUnsupportedMediaType() {
		val response = send("/v1/logs", "text/csv", "{}".toByteArray())

		assertThat(response.statusCode()).isEqualTo(415)
		assertThat(response.body()).startsWith("415 unsupported media type, supported: [")
	}

	@Test
	@DisplayName("POST 가 아니면 수집 모듈의 405 다")
	fun aNonPostIsTheCollectorsMethodNotAllowed() {
		val request = authorized("/v1/logs").GET().build()

		val response = http.send(request, HttpResponse.BodyHandlers.ofString())

		assertThat(response.statusCode()).isEqualTo(405)
		assertThat(response.body()).isEqualTo("405 method not allowed, supported: [POST]")
	}

	@Test
	@DisplayName("원본 바디 상한을 넘으면 413 이다 — 압축 해제 전에 끊는다")
	fun anOversizedBodyIsRejectedBeforeDecompression() {
		val oversized = ByteArray(11 * 1024 * 1024) { '{'.code.toByte() }

		val response = sendBytes("/v1/logs", "application/json", oversized)

		assertThat(response.statusCode()).isEqualTo(413)
		assertThat(response.body()).isEqualTo("413 request entity too large")
	}

	// ------------------------------------------------------------------ 도구

	private fun send(path: String, contentType: String, body: ByteArray): HttpResponse<String> =
		sendBytes(path, contentType, body)

	private fun sendBytes(path: String, contentType: String, body: ByteArray): HttpResponse<String> {
		val request = authorized(path)
			.header("Content-Type", contentType)
			.POST(HttpRequest.BodyPublishers.ofByteArray(body))
			.build()
		return http.send(request, HttpResponse.BodyHandlers.ofString())
	}

	private fun authorized(path: String): HttpRequest.Builder =
		HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
			.header("Authorization", "Bearer $token")
}
