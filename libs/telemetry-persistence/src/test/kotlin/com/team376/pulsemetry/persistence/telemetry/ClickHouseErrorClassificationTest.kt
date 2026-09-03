package com.team376.pulsemetry.persistence.telemetry

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * **오류 분류를 고치지 말고 그대로 고정한다.**
 *
 * 4xx 까지 전부 [TelemetrySinkUnavailableException] 이고 앱이 그것을 503 으로 돌린다. 영구
 * 오류(400)로 돌려보내면 업스트림이 배치를 폐기하므로, 유실 없는 쪽으로 치우친 것이다.
 * 이식 원본의 같은 테스트가 *"고치지 말고 그대로 고정한다"* 로 못박았다.
 *
 * 보강 단계는 정반대로 좁다 — `OrgProviderErrorClassificationTest` 와 나란히 읽는다.
 *
 * ClickHouse 없이 돈다. 판정 대상이 상태 코드의 분류이지 ClickHouse 의 동작이 아니다.
 */
class ClickHouseErrorClassificationTest {

	private lateinit var server: HttpServer
	private var status: Int = 200
	private var body: String = ""

	@BeforeEach
	fun startStub() {
		server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
		server.createContext("/") { exchange ->
			val bytes = body.toByteArray(StandardCharsets.UTF_8)
			exchange.sendResponseHeaders(status, bytes.size.toLong())
			exchange.responseBody.use { it.write(bytes) }
		}
		server.start()
	}

	@AfterEach
	fun stopStub() {
		server.stop(0)
	}

	private fun client(): ClickHouseHttpClient =
		ClickHouseHttpClient("http://127.0.0.1:${server.address.port}")

	@ParameterizedTest(name = "HTTP {0}")
	@ValueSource(ints = [400, 401, 403, 404, 413, 500, 502, 503])
	@DisplayName("어떤 오류 상태든 일시 장애로 분류한다 — 4xx 도 마찬가지다")
	fun everyErrorStatusBecomesUnavailable(code: Int) {
		status = code
		body = "Code: 62. DB::Exception: Syntax error"

		assertThatThrownBy { client().execute("SELECT 1") }
			.isInstanceOf(TelemetrySinkUnavailableException::class.java)
			.hasMessageContaining("clickhouse $code")
	}

	@Test
	@DisplayName("오류 본문은 잘라서 싣는다 — ClickHouse 는 스택 트레이스까지 돌려준다")
	fun theErrorDetailIsTruncated() {
		status = 500
		body = "x".repeat(5_000)

		assertThatThrownBy { client().execute("SELECT 1") }
			.isInstanceOf(TelemetrySinkUnavailableException::class.java)
			.satisfies({ assertThat(it.message).hasSizeLessThan(700) })
	}

	@Test
	@DisplayName("서버에 닿지 못해도 같은 분류다")
	fun anUnreachableServerIsTheSameClassification() {
		val port = server.address.port
		server.stop(0)

		assertThatThrownBy { ClickHouseHttpClient("http://127.0.0.1:$port").execute("SELECT 1") }
			.isInstanceOf(TelemetrySinkUnavailableException::class.java)
			.hasMessageContaining("unreachable")
	}

	@Test
	@DisplayName("성공 응답은 본문을 그대로 돌려준다")
	fun successReturnsTheBody() {
		status = 200
		body = "42\n"

		assertThat(client().execute("SELECT 42")).isEqualTo("42\n")
	}
}
