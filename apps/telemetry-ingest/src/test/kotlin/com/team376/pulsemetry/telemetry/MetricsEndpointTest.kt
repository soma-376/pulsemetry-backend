package com.team376.pulsemetry.telemetry

import com.team376.pulsemetry.telemetry.support.AbstractIngestIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

class MetricsEndpointTest : AbstractIngestIntegrationTest() {
    @LocalServerPort private var port: Int = 0
    @Value("\${local.management.port}") private var managementPort: Int = 0
    private val http = HttpClient.newHttpClient()
    private fun get(port: Int, path: String) = send(port, "GET", path)
    private fun send(port: Int, method: String, path: String) =
        http.send(HttpRequest.newBuilder(URI("http://localhost:$port$path")).method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())

    @Test
    fun scrapeOnlyOnManagementPort() {
        assertThat(managementPort).isNotEqualTo(port)
        val scrape = get(managementPort, "/actuator/prometheus")
        assertThat(scrape.statusCode()).isEqualTo(200)
        assertThat(scrape.body()).contains("jvm_memory_used_bytes", "pulsemetry_pipeline_inflight", "pulsemetry_pipeline_listener_running", "tomcat_threads_busy_threads", "hikaricp_connections_active", "jdbc_connections_active")
        // 실패가 없어도 경보 대상 counter가 있어야 첫 실패가 increase()에 잡힌다(TelemetryFailureReporter).
        assertThat(scrape.body()).contains("pulsemetry_pipeline_retry_exhausted_total{", "pulsemetry_pipeline_commit_failures_total{")
        assertThat(scrape.body()).doesNotContain("pulsemetry_gateway_")
        Files.createDirectories(Path.of("build"))
        Files.writeString(Path.of("build/management-scrape.prom"), scrape.body())
        // 관리 체인(SecurityConfig)이 관리 컨텍스트에도 걸려 GET scrape 밖은 기본 닫힘과 같은 404 다.
        for ((method, path) in listOf("POST" to "/actuator/prometheus", "PUT" to "/actuator/prometheus", "GET" to "/actuator/health",
            "GET" to "/actuator/env", "GET" to "/actuator", "GET" to "/api/v1/healthz", "POST" to "/v1/logs")) {
            val response = send(managementPort, method, path)
            assertThat(response.statusCode()).describedAs("$method $path").isEqualTo(404)
            assertThat(response.body()).describedAs("$method $path").isEqualTo("404 not found")
        }
        assertThat(get(port, "/api/v1/healthz").statusCode()).isEqualTo(200)
    }

    @Test
    fun businessPortActuatorIsTheContractNotFound() {
        for ((method, path) in listOf("GET" to "/actuator", "GET" to "/actuator/prometheus", "POST" to "/actuator/prometheus")) {
            val response = send(port, method, path)
            assertThat(response.statusCode()).describedAs(path).isEqualTo(404)
            assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying { assertThat(it).startsWith("text/plain") }
            assertThat(response.body()).isEqualTo("404 not found")
        }
    }
}
