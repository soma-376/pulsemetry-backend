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
    private fun get(port: Int, path: String) = http.send(HttpRequest.newBuilder(URI("http://localhost:$port$path")).GET().build(), HttpResponse.BodyHandlers.ofString())

    @Test
    fun scrapeOnlyOnManagementPort() {
        assertThat(managementPort).isNotEqualTo(port)
        val scrape = get(managementPort, "/actuator/prometheus")
        assertThat(scrape.statusCode()).isEqualTo(200)
        assertThat(scrape.body()).contains("jvm_memory_used_bytes", "pulsemetry_gateway_inflight", "tomcat_threads_busy_threads", "hikaricp_connections_active", "jdbc_connections_active")
        Files.createDirectories(Path.of("build"))
        Files.writeString(Path.of("build/management-scrape.prom"), scrape.body())
        val post = http.send(HttpRequest.newBuilder(URI("http://localhost:$managementPort/actuator/prometheus")).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
        assertThat(post.statusCode()).isEqualTo(404)
        assertThat(get(port, "/actuator/prometheus").statusCode()).isEqualTo(404)
        for (path in listOf("/actuator/health", "/actuator/env", "/actuator", "/api/v1/healthz", "/v1/logs")) {
            assertThat(get(managementPort, path).statusCode()).describedAs(path).isEqualTo(404)
        }
        assertThat(get(port, "/api/v1/healthz").statusCode()).isEqualTo(200)
    }
}
