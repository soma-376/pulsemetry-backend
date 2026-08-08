package com.team376.pulsemetry.enrollment.api

import com.team376.pulsemetry.support.PostgresContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.AbstractDataSource
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.SQLException

/**
 * `GET /v1/healthz` (PLAN.md §6.4).
 *
 * 정상 경로는 실제 HTTP 로, 장애 경로는 컨트롤러를 직접 만들어 확인한다 —
 * 테스트 도중 컨테이너를 죽이면 같은 컨텍스트를 쓰는 다른 테스트까지 무너진다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresContainerConfig::class)
class HealthzApiTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var objectMapper: ObjectMapper

	private val http: HttpClient = HttpClient.newHttpClient()

	@Test
	@DisplayName("DB 가 살아 있으면 200 과 status=ok")
	fun healthyReturnsOk() {
		val response = get("/v1/healthz")

		assertThat(response.statusCode()).isEqualTo(200)
		val body = objectMapper.readTree(response.body())
		assertThat(body.get("status").asString()).isEqualTo("ok")
		assertThat(body.get("checks").get("database").asString()).isEqualTo("ok")
	}

	@Test
	@DisplayName("본문은 status·checks 두 필드뿐이다")
	fun bodyShape() {
		val body = objectMapper.readTree(get("/v1/healthz").body())

		assertThat(body.propertyNames()).containsExactlyInAnyOrder("status", "checks")
		assertThat(body.get("checks").propertyNames()).containsExactly("database")
	}

	@Test
	@DisplayName("인증 없이 호출된다")
	fun requiresNoAuthentication() {
		assertThat(get("/v1/healthz").statusCode()).isEqualTo(200)
	}

	@Test
	@DisplayName("DB 프로브가 실패하면 503 과 degraded 다 — 예외를 밖으로 흘리지 않는다")
	fun brokenDatabaseReturnsDegraded() {
		val controller = HealthController(JdbcClient.create(FailingDataSource()))

		val response = controller.healthz()

		assertThat(response.statusCode.value()).isEqualTo(503)
		assertThat(response.body?.status).isEqualTo("degraded")
		assertThat(response.body?.checks).containsEntry("database", "down")
	}

	private fun get(path: String): HttpResponse<String> =
		http.send(
			HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().build(),
			HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
		)

	/** 커넥션을 못 얻는 상황을 흉내 낸다. */
	private class FailingDataSource : AbstractDataSource() {
		override fun getConnection(): Connection = throw SQLException("database is down")

		override fun getConnection(username: String?, password: String?): Connection =
			throw SQLException("database is down")
	}
}
