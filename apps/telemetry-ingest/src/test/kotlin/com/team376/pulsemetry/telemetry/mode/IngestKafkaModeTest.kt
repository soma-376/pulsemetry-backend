package com.team376.pulsemetry.telemetry.mode

import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
import com.team376.pulsemetry.security.TelemetryTokenHasher
import com.team376.pulsemetry.telemetry.config.KafkaConsumerConfig
import com.team376.pulsemetry.telemetry.credential.TelemetryBearerAuthenticationProvider
import com.team376.pulsemetry.telemetry.support.AbstractIngestIntegrationTest
import com.team376.pulsemetry.telemetry.support.IngestTestKafka
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * `kafka` 모드(ADR 0066 Decision 7) — Consumer 와 health 만 있고 OTLP 경로는 기본 닫힘 404 다. 해시 키·telemetry 공개키 없이 뜬다.
 *
 * 다른 테스트와 consumer group·topic 을 나누지 않으면 같은 그룹의 소비자가 둘이 되어 record 를 나눠 가진다 — 그래서 이 컨텍스트만의
 * group·topic 을 쓴다(두 컨텍스트가 같은 JVM 에 함께 산다).
 */
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = [
		"pulsemetry.telemetry.ingest.mode=kafka",
		"pulsemetry.token-hash-secret=",
		"pulsemetry.telemetry.ops.enabled=false",
		"pulsemetry.telemetry.kafka.topic=${IngestKafkaModeTest.TOPIC}",
		"pulsemetry.telemetry.kafka.consumer-group=pulsemetry-test-kafka-mode",
	],
)
@Import(PostgresContainerConfig::class)
class IngestKafkaModeTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var context: ApplicationContext

	@Autowired
	@Qualifier(KafkaConsumerConfig.CONTAINER_BEAN)
	private lateinit var container: ConcurrentMessageListenerContainer<ByteArray, ByteArray>

	private val http: HttpClient = HttpClient.newHttpClient()

	@Test
	@DisplayName("OTLP 경로는 404 이고 health 는 200 이며 listener 가 돈다")
	fun kafkaOnly() {
		val otlp = http.send(
			HttpRequest.newBuilder(URI.create("http://localhost:$port/v1/logs"))
				.header("Content-Type", "application/json").header("Authorization", "Bearer ptt_x")
				.POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
			HttpResponse.BodyHandlers.ofString(),
		)
		assertThat(otlp.statusCode()).isEqualTo(404)
		assertThat(otlp.body()).isEqualTo("404 not found")

		val health = http.send(HttpRequest.newBuilder(URI.create("http://localhost:$port/api/v1/healthz")).GET().build(), HttpResponse.BodyHandlers.ofString())
		assertThat(health.statusCode()).isEqualTo(200)
		assertThat(container.isRunning).isTrue()
	}

	@Test
	@DisplayName("해시 키·해시 조회·JWT 검증 빈을 만들지 않는다")
	fun noHttpCredentialBeans() {
		assertThat(context.getBeanNamesForType(TelemetryTokenHasher::class.java)).isEmpty()
		assertThat(context.getBeanNamesForType(TelemetryBearerAuthenticationProvider::class.java)).isEmpty()
	}

	companion object {
		const val TOPIC: String = "pulsemetry-test-kafka-mode"

		@JvmStatic
		@DynamicPropertySource
		fun properties(registry: DynamicPropertyRegistry) {
			IngestTestKafka.createTopic(TOPIC)
			registry.add("pulsemetry.telemetry.kafka.bootstrap-servers") { IngestTestKafka.container.bootstrapServers }
			registry.add("pulsemetry.telemetry.clickhouse.url") { AbstractIngestIntegrationTest.clickHouseUrl() }
		}
	}
}
