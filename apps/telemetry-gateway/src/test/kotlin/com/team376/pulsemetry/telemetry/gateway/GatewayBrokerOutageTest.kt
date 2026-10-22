package com.team376.pulsemetry.telemetry.gateway

import com.team376.pulsemetry.telemetry.gateway.support.GatewayTestKafka
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * broker 가 응답하지 않을 때 Gateway 가 데몬의 요청 제한(10초) 안에 503 + `Retry-After` 를 내는지 본다(ADR 0066 Decision 3).
 * 컨테이너를 멈추므로 다른 테스트와 Kafka 를 공유하지 않는다 — 설정이 달라 컨텍스트도 따로 뜬다.
 */
@ExtendWith(OutputCaptureExtension::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["management.server.port=0"])
class GatewayBrokerOutageTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var registry: MeterRegistry

	private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

	private fun post(): HttpResponse<String> {
		val token = GatewayTestKafka.KEY.issuer().issue(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.now()).token
		val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/v1/logs"))
			.timeout(Duration.ofSeconds(10))
			.header("Content-Type", "application/json")
			.header("Authorization", "Bearer $token")
			.POST(HttpRequest.BodyPublishers.ofString("""{"resourceLogs":[]}"""))
			.build()
		return http.send(request, HttpResponse.BodyHandlers.ofString())
	}

	@Test
	@DisplayName("broker 가 멈추면 기한 안에 503 + Retry-After 이고 다시 살아나면 200 이다")
	fun brokerPausedYields503WithinDeadline(output: CapturedOutput) {
		// metadata 를 한 번 받아 둔다 — 멈춘 뒤의 실패가 max.block 이 아니라 delivery·기한에서 나는 경우를 본다.
		assertThat(post().statusCode()).isEqualTo(200)

		val docker = kafka.dockerClient
		docker.pauseContainerCmd(kafka.containerId).exec()
		try {
			val failedBefore = publishes("failed")
			val started = System.nanoTime()
			val response = post()
			val elapsed = Duration.ofNanos(System.nanoTime() - started)

			assertThat(response.statusCode()).isEqualTo(503)
			assertThat(response.headers().firstValue("Retry-After")).hasValue("1")
			assertThat(elapsed).isLessThan(Duration.ofMillis(9500))
			assertThat(output.all).contains("event=telemetry_publish_failed")
			// ack 를 확인하지 못한 발행은 failed 다 — 실제로 발행됐을 가능성은 남는다(사유는 시간 예산 쪽).
			assertThat(publishes("failed") - failedBefore).isEqualTo(1.0)
			assertThat(registry.find("pulsemetry.gateway.publish").tags("outcome", "failed").counters().map { it.id.getTag("reason") })
				.isSubsetOf("delivery_timeout", "deadline_exceeded", "metadata_timeout", "broker_error")
		} finally {
			docker.unpauseContainerCmd(kafka.containerId).exec()
		}

		// 회복 뒤에는 다시 받는다(재연결에 시간이 걸릴 수 있다).
		val recoveredBy = System.nanoTime() + Duration.ofSeconds(30).toNanos()
		var status = 0
		while (System.nanoTime() < recoveredBy) {
			status = post().statusCode()
			if (status == 200) break
		}
		assertThat(status).isEqualTo(200)
	}

	private fun publishes(outcome: String): Double =
		registry.find("pulsemetry.gateway.publish").tags("outcome", outcome).counters().sumOf { it.count() }

	companion object {
		private val kafka = GatewayTestKafka.container().also {
			it.start()
			GatewayTestKafka.createTopic(it.bootstrapServers)
		}

		@JvmStatic
		@DynamicPropertySource
		fun properties(registry: DynamicPropertyRegistry) {
			registry.add("pulsemetry.telemetry.kafka.bootstrap-servers") { kafka.bootstrapServers }
			registry.add("pulsemetry.telemetry.kafka.topic") { GatewayTestKafka.TOPIC }
			registry.add("pulsemetry.telemetry-jwt.public-key-files.${GatewayTestKafka.KID}") { GatewayTestKafka.PUBLIC_PEM.toString() }
		}
	}
}
