package com.team376.pulsemetry.telemetry.gateway.publish

import com.team376.pulsemetry.telemetry.gateway.config.GatewayConfig
import com.team376.pulsemetry.telemetry.gateway.config.TelemetryGatewayProperties
import com.team376.pulsemetry.telemetry.ingress.AcceptedTelemetry
import com.team376.pulsemetry.telemetry.ingress.GatewayAuthContext
import com.team376.pulsemetry.telemetry.ingress.RawOtlpPayload
import com.team376.pulsemetry.telemetry.ingress.TelemetryKafkaSecurity
import com.team376.pulsemetry.telemetry.otlp.OtlpMediaType
import com.team376.pulsemetry.telemetry.otlp.OtlpSignal
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** producer 설정과 broker 가 없을 때의 실패 시간(ADR 0066 Decision 3·9). */
class KafkaTelemetryPublisherTest {

	private fun properties(kafka: TelemetryGatewayProperties.Kafka) = TelemetryGatewayProperties(
		telemetryJwt = TelemetryGatewayProperties.TelemetryJwt(mapOf("k" to "/unused")),
		telemetry = TelemetryGatewayProperties.Telemetry(kafka = kafka),
	)

	private fun message() = AcceptedTelemetry(
		messageId = UUID.randomUUID(),
		acceptedAt = Instant.now(),
		auth = GatewayAuthContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "pulsemetry-enrollment", "k", Instant.now(), Instant.now().plusSeconds(60)),
		payload = RawOtlpPayload(OtlpSignal.LOGS, OtlpMediaType.JSON, "", "{}".toByteArray()),
	)

	@Test
	@DisplayName("producer 설정은 acks=all·idempotence·시간 예산·크기 상한이다")
	fun producerSettings() {
		val props = GatewayConfig.producerProperties(properties(TelemetryGatewayProperties.Kafka()))

		assertThat(props).containsAllEntriesOf(
			mapOf(
				"acks" to "all", "enable.idempotence" to true, "max.block.ms" to 2000L, "request.timeout.ms" to 4000,
				"delivery.timeout.ms" to 5000, "buffer.memory" to 64L * 1024 * 1024, "max.request.size" to 12 * 1024 * 1024,
				"compression.type" to "none", "security.protocol" to "PLAINTEXT", "client.id" to "pulsemetry-telemetry-gateway",
			),
		)
	}

	@Test
	@DisplayName("MSK IAM 설정으로 producer 를 만들 수 있다 — 로그인 모듈·콜백 핸들러 클래스가 런타임에 있다")
	fun iamClassesLoad() {
		val iam = TelemetryGatewayProperties.Kafka(
			bootstrapServers = "localhost:9098",
			security = TelemetryKafkaSecurity.IAM,
			iam = TelemetryGatewayProperties.Iam(
				roleArn = "arn:aws:iam::123456789012:role/pulsemetry-dev-telemetry-gateway-msk",
				sessionName = "telemetry-gateway",
			),
		)
		val props = GatewayConfig.producerProperties(properties(iam))

		assertThat(Class.forName("software.amazon.msk.auth.iam.IAMLoginModule")).isNotNull()
		assertThat(Class.forName("software.amazon.msk.auth.iam.IAMClientCallbackHandler")).isNotNull()
		// 생성자가 JAAS 를 해석하고 SASL 채널을 만든다 — 클래스가 없거나 JAAS 가 깨지면 여기서 실패한다. 네트워크는 쓰지 않는다.
		KafkaProducer(props, StringSerializer(), ByteArraySerializer()).close(Duration.ZERO)
	}

	@Test
	@DisplayName("broker 가 없으면 max.block.ms(2초) 근처에서 실패한다 — 기한(7.5초)보다 먼저 503 을 낼 수 있다")
	fun unreachableBroker() {
		val port = ServerSocket(0).use { it.localPort }
		val props = properties(TelemetryGatewayProperties.Kafka(bootstrapServers = "127.0.0.1:$port"))
		val publisher = KafkaTelemetryPublisher(
			KafkaProducer(GatewayConfig.producerProperties(props), StringSerializer(), ByteArraySerializer()),
			"pulsemetry-test-telemetry-ingress",
			Clock.systemUTC(),
			Duration.ofSeconds(1),
		)
		publisher.use {
			val started = System.nanoTime()
			assertThatThrownBy { it.publish(message(), Instant.now().plusMillis(7500)) }
				.isInstanceOfSatisfying(TelemetryPublishException::class.java) {
					assertThat(it.reason).isIn("metadata_timeout", "delivery_timeout")
				}
			assertThat(Duration.ofNanos(System.nanoTime() - started)).isBetween(Duration.ofMillis(1500), Duration.ofMillis(7500))
		}
	}

	@Test
	@DisplayName("기한이 이미 지났으면 보내지 않고 deadline_exceeded 다")
	fun pastDeadline() {
		val props = properties(TelemetryGatewayProperties.Kafka(bootstrapServers = "127.0.0.1:1"))
		KafkaTelemetryPublisher(
			KafkaProducer(GatewayConfig.producerProperties(props), StringSerializer(), ByteArraySerializer()),
			"t", Clock.systemUTC(), Duration.ZERO,
		).use { publisher ->
			assertThatThrownBy { publisher.publish(message(), Instant.now().minusMillis(1)) }
				.isInstanceOfSatisfying(TelemetryPublishException::class.java) { assertThat(it.reason).isEqualTo("deadline_exceeded") }
		}
	}
}
