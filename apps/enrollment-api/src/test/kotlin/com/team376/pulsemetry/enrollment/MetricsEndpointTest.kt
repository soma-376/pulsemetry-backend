package com.team376.pulsemetry.enrollment

import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

/** 관리 포트 Prometheus scrape(ADR 0068·0079). 업무 포트의 알 수 없는 경로는 그대로 404 `not_found` 다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresContainerConfig::class)
class MetricsEndpointTest {

    @LocalServerPort
    private var port: Int = 0

    @Value("\${local.management.port}")
    private var managementPort: Int = 0

    private val http: HttpClient = HttpClient.newHttpClient()

    private fun send(port: Int, method: String, path: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI("http://localhost:$port$path")).method(method, HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    @DisplayName("관리 포트에서만 scrape 되고 JVM·Tomcat·Hikari·HTTP 지연 버킷이 있다")
    fun scrapeOnlyOnManagementPort() {
        assertThat(managementPort).isNotEqualTo(port)
        assertThat(send(port, "GET", "/api/v1/healthz").statusCode()).isEqualTo(200)

        val scrape = send(managementPort, "GET", "/actuator/prometheus")
        assertThat(scrape.statusCode()).isEqualTo(200)
        assertThat(scrape.body()).contains(
            "jvm_memory_used_bytes", "tomcat_threads_busy_threads", "jdbc_connections_active",
            "hikaricp_connections_pending", "hikaricp_connections_acquire_seconds",
            """service="enrollment"""", """application="pulsemetry-enrollment-api"""",
        )
        // 고정 SLO 버킷(50ms~10s)으로 route별 p95 를 낸다(ADR 0079).
        assertThat(scrape.body()).containsPattern("""http_server_requests_seconds_bucket\{[^}]*le="10\.0"""")
        assertThat(scrape.body()).doesNotContain("pulsemetry_gateway_", "pulsemetry_pipeline_")
        // infra 검증기의 scrape fixture 원본이다(helm/observability/tests/fixtures).
        Files.createDirectories(Path.of("build"))
        Files.writeString(Path.of("build/management-scrape.prom"), scrape.body())

        // 관리 체인(ObservabilityConfig)이 관리 컨텍스트에도 걸려 GET scrape 밖은 404 text/plain 이다.
        for ((method, path) in listOf("POST" to "/actuator/prometheus", "GET" to "/actuator/health", "GET" to "/actuator/env",
            "GET" to "/actuator", "GET" to "/api/v1/healthz", "POST" to "/api/v1/enroll")) {
            val response = send(managementPort, method, path)
            assertThat(response.statusCode()).describedAs("$method $path").isEqualTo(404)
            assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying { assertThat(it).startsWith("text/plain") }
            assertThat(response.body()).describedAs("$method $path").isEqualTo("404 not found")
        }
    }

    @Test
    @DisplayName("trace 를 꺼도 health 요청의 http.server.requests 지표가 남는다")
    fun healthRequestsKeepHttpServerMetrics() {
        assertThat(send(port, "GET", "/api/v1/healthz").statusCode()).isEqualTo(200)
        assertThat(send(managementPort, "GET", "/actuator/prometheus").body())
            .containsPattern("""http_server_requests_seconds_count\{[^}]*uri="/api/v1/healthz"""")
    }

    @Test
    @DisplayName("업무 포트의 actuator 는 알 수 없는 경로와 같은 404 not_found 다")
    fun businessPortActuatorIsNotFound() {
        for (path in listOf("/actuator", "/actuator/prometheus")) {
            val response = send(port, "GET", path)
            assertThat(response.statusCode()).describedAs(path).isEqualTo(404)
            // 에러 계약은 {"error": 코드, "message": 문구} 다(ErrorContractApiTest).
            assertThat(JsonMapper.builder().build().readTree(response.body()).path("error").asString()).isEqualTo("not_found")
            assertThat(response.body()).doesNotContain("jvm_")
        }
    }
}
