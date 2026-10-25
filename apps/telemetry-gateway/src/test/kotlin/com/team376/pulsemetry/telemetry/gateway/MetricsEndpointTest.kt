package com.team376.pulsemetry.telemetry.gateway

import com.team376.pulsemetry.telemetry.gateway.support.AbstractGatewayIntegrationTest
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

class MetricsEndpointTest : AbstractGatewayIntegrationTest() {
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
        assertThat(scrape.body()).contains("jvm_memory_used_bytes", "pulsemetry_gateway_inflight", "tomcat_threads_busy_threads")
        assertThat(scrape.body()).doesNotContain("pulsemetry_pipeline_")
        Files.createDirectories(Path.of("build"))
        Files.writeString(Path.of("build/management-scrape.prom"), scrape.body())
        // 관리 컨텍스트에는 부모의 서블릿 필터가 걸리지 않는다 — 다른 메서드는 MVC 의 405, 다른 경로는 Boot 오류 응답의 404 다.
        for (method in listOf("POST", "PUT", "DELETE")) {
            assertThat(send(managementPort, method, "/actuator/prometheus").statusCode()).describedAs(method).isEqualTo(405)
        }
        for (path in listOf("/actuator/health", "/actuator/env", "/actuator", "/api/v1/healthz", "/v1/logs")) {
            assertThat(get(managementPort, path).statusCode()).describedAs(path).isEqualTo(404)
        }
        assertThat(get(port, "/api/v1/healthz").statusCode()).isEqualTo(200)
    }

    @Test
    fun healthRequestsKeepHttpServerMetrics() {
        // trace를 꺼도(기본) health 경로의 http.server.requests 지표가 남는다. trace는 span만 뺀다(ADR 0069).
        assertThat(get(port, "/api/v1/healthz").statusCode()).isEqualTo(200)
        val scrape = get(managementPort, "/actuator/prometheus").body()
        assertThat(scrape).containsPattern("""http_server_requests_seconds_count\{[^}]*uri="/api/v1/healthz"""")
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
