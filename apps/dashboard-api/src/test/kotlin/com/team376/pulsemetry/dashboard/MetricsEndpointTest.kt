package com.team376.pulsemetry.dashboard

import com.team376.pulsemetry.dashboard.support.AbstractDashboardApiTest
import com.team376.pulsemetry.dashboard.support.DashboardHttp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.web.server.LocalServerPort
import java.nio.file.Files
import java.nio.file.Path

/** 관리 포트 Prometheus scrape(ADR 0068·0079). 업무 포트의 계약 밖 경로는 그대로 404 `not_found` 다. */
class MetricsEndpointTest : AbstractDashboardApiTest() {

	@LocalServerPort
	private var port: Int = 0

	@Value("\${local.management.port}")
	private var managementPort: Int = 0

	private val management: DashboardHttp by lazy { DashboardHttp(managementPort) }

	@Test
	@DisplayName("관리 포트에서만 scrape 되고 JVM·Tomcat·두 RDS 풀·HTTP 지연 버킷이 있다")
	fun scrapeOnlyOnManagementPort() {
		assertThat(managementPort).isNotEqualTo(port)
		assertThat(http.send("/api/v1/healthz").statusCode()).isEqualTo(200)

		val scrape = management.send("/actuator/prometheus")
		assertThat(scrape.statusCode()).isEqualTo(200)
		assertThat(scrape.body()).contains(
			"jvm_memory_used_bytes", "tomcat_threads_busy_threads", "jdbc_connections_active",
			"hikaricp_connections_pending", "hikaricp_connections_acquire_seconds",
			"""pool="dashboard-rds-source"""", """pool="dashboard-rds-cache"""",
			"""service="dashboard"""", """application="pulsemetry-dashboard-api"""",
		)
		// 고정 SLO 버킷(50ms~10s)으로 route별 p95 를 낸다(ADR 0079).
		assertThat(scrape.body()).containsPattern("""http_server_requests_seconds_bucket\{[^}]*le="10\.0"""")
		assertThat(scrape.body()).doesNotContain("pulsemetry_gateway_", "pulsemetry_pipeline_")
		// infra 검증기의 scrape fixture 원본이다(helm/observability/tests/fixtures).
		Files.createDirectories(Path.of("build"))
		Files.writeString(Path.of("build/management-scrape.prom"), scrape.body())

		// 관리 체인(ObservabilityConfig)이 관리 컨텍스트에도 걸려 GET scrape 밖은 404 text/plain 이다.
		for ((method, path) in listOf("POST" to "/actuator/prometheus", "GET" to "/actuator/health", "GET" to "/actuator/env",
			"GET" to "/actuator", "GET" to "/api/v1/healthz", "GET" to "/api/v1/organizations/00000000-0000-0000-0000-000000000000/alerts")) {
			val response = management.send(path, method = method)
			assertThat(response.statusCode()).describedAs("$method $path").isEqualTo(404)
			assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying { assertThat(it).startsWith("text/plain") }
			assertThat(response.body()).describedAs("$method $path").isEqualTo("404 not found")
		}
	}

	@Test
	@DisplayName("trace 를 꺼도 health 요청의 http.server.requests 지표가 남는다")
	fun healthRequestsKeepHttpServerMetrics() {
		assertThat(http.send("/api/v1/healthz").statusCode()).isEqualTo(200)
		assertThat(management.send("/actuator/prometheus").body())
			.containsPattern("""http_server_requests_seconds_count\{[^}]*uri="/api/v1/healthz"""")
	}

	@Test
	@DisplayName("업무 포트의 actuator 는 기존 계약 밖 경로와 같은 404 not_found 다")
	fun businessPortActuatorIsNotFound() {
		for ((method, path) in listOf("GET" to "/actuator", "GET" to "/actuator/prometheus", "POST" to "/actuator/prometheus")) {
			val response = http.send(path, method = method)
			assertThat(response.statusCode()).describedAs(path).isEqualTo(404)
			assertThat(DashboardHttp.json(response).path("error").path("code").asString()).isEqualTo("not_found")
			assertThat(response.body()).doesNotContain("jvm_")
		}
	}
}
