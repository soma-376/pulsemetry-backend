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
 * **오류 분류를 고정한다.**
 *
 * 연결 계열과 `5xx · 429 · 408` 은 [TelemetrySinkUnavailableException](일시 장애 → 503),
 * 그 밖의 4xx 는 [TelemetrySinkRejectedException](영구 오류 → 400)이다.
 *
 * 근거는 허브 ADR 0006 이다. 그 ADR 없이 분류를 넓히지 마라. 보강 단계도 같은 원칙이다 —
 * `OrgProviderErrorClassificationTest` 와 나란히 읽는다.
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
	@ValueSource(ints = [500, 502, 503, 504, 429, 408])
	@DisplayName("5xx·429·408 은 일시 장애다 — 다시 보내면 답이 달라질 수 있다")
	fun serverAndOverloadStatusesBecomeUnavailable(code: Int) {
		status = code
		body = "Code: 210. DB::NetException: Connection reset"

		assertThatThrownBy { client().execute("SELECT 1") }
			.isInstanceOf(TelemetrySinkUnavailableException::class.java)
			.hasMessageContaining("clickhouse $code")
	}

	@ParameterizedTest(name = "HTTP {0}")
	@ValueSource(ints = [400, 401, 403, 404, 413, 415, 422])
	@DisplayName("그 밖의 4xx 는 영구 오류다 — 같은 배치를 다시 보내도 같은 답이 온다")
	fun otherClientErrorStatusesBecomeRejected(code: Int) {
		status = code
		body = "Code: 62. DB::Exception: Syntax error"

		assertThatThrownBy { client().execute("SELECT 1") }
			.isInstanceOf(TelemetrySinkRejectedException::class.java)
			.hasMessageContaining("clickhouse $code")
	}

	@Test
	@DisplayName("스키마 불일치(400)가 일시 장애로 위장하지 않는다")
	fun aSchemaMismatchIsNotTransient() {
		status = 400
		body = "Code: 16. DB::Exception: No such column team_ids_as_of"

		assertThatThrownBy { client().execute("INSERT INTO enriched_events FORMAT JSONEachRow") }
			.isInstanceOf(TelemetrySinkRejectedException::class.java)
			.isNotInstanceOf(TelemetrySinkUnavailableException::class.java)
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
