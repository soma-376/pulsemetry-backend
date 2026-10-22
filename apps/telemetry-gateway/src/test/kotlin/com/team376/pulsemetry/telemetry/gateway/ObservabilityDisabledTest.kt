package com.team376.pulsemetry.telemetry.gateway

import com.team376.pulsemetry.telemetry.gateway.support.AbstractGatewayIntegrationTest
import com.team376.pulsemetry.telemetry.gateway.support.GatewayTestKafka
import com.team376.pulsemetry.telemetry.observability.TelemetryObserver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.TestPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID

/** 계측을 끄면 `pulsemetry_*` 만 사라지고 JVM·Tomcat·Kafka client 지표와 scrape 는 남는다(ADR 0068). 설정이 달라 컨텍스트가 따로 뜬다. */
@TestPropertySource(properties = ["pulsemetry.observability.enabled=false"])
class ObservabilityDisabledTest : AbstractGatewayIntegrationTest() {
    @LocalServerPort private var port: Int = 0
    @Value("\${local.management.port}") private var managementPort: Int = 0
    @Autowired private lateinit var observer: TelemetryObserver
    private val http = HttpClient.newHttpClient()

    @Test
    fun customMetricsOffBindersOn() {
        assertThat(observer).isSameAs(TelemetryObserver.NONE)
        val token = GatewayTestKafka.KEY.issuer().issue(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.now()).token
        val accepted = http.send(
            HttpRequest.newBuilder(URI("http://localhost:$port/v1/logs")).header("Content-Type", "application/json")
                .header("Authorization", "Bearer $token").POST(HttpRequest.BodyPublishers.ofString("""{"resourceLogs":[]}""")).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertThat(accepted.statusCode()).isEqualTo(200)

        val scrape = http.send(HttpRequest.newBuilder(URI("http://localhost:$managementPort/actuator/prometheus")).GET().build(), HttpResponse.BodyHandlers.ofString())
        assertThat(scrape.statusCode()).isEqualTo(200)
        assertThat(scrape.body()).contains("jvm_memory_used_bytes", "tomcat_threads_busy_threads", "kafka_producer_", "http_server_requests_seconds")
        assertThat(scrape.body()).doesNotContain("pulsemetry_")
    }
}
