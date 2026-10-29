package com.team376.pulsemetry.telemetry.gateway.publish

import com.team376.pulsemetry.telemetry.gateway.config.GatewayConfig
import com.team376.pulsemetry.telemetry.gateway.config.TelemetryGatewayProperties
import com.team376.pulsemetry.telemetry.ingress.AcceptedTelemetry
import com.team376.pulsemetry.telemetry.ingress.GatewayAuthContext
import com.team376.pulsemetry.telemetry.ingress.KafkaTlsTestFiles
import com.team376.pulsemetry.telemetry.ingress.RawOtlpPayload
import com.team376.pulsemetry.telemetry.ingress.TelemetryKafkaSecurity
import com.team376.pulsemetry.telemetry.observability.MicrometerTelemetryObserver
import com.team376.pulsemetry.telemetry.otlp.OtlpMediaType
import com.team376.pulsemetry.telemetry.otlp.OtlpSignal
import io.micrometer.core.instrument.binder.kafka.KafkaClientMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext
import io.micrometer.tracing.otel.bridge.OtelTracer
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.samplers.Sampler
import java.net.ServerSocket
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource

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
		val registry = SimpleMeterRegistry()
		val publisher = KafkaTelemetryPublisher(
			KafkaProducer(GatewayConfig.producerProperties(props), StringSerializer(), ByteArraySerializer()),
			"pulsemetry-test-telemetry-ingress",
			Clock.systemUTC(),
			Duration.ofSeconds(1),
			observer = MicrometerTelemetryObserver(registry),
		)
		publisher.use {
			val started = System.nanoTime()
			var reason = ""
			assertThatThrownBy { it.publish(message(), Instant.now().plusMillis(7500)) }
				.isInstanceOfSatisfying(TelemetryPublishException::class.java) {
					assertThat(it.reason).isIn("metadata_timeout", "delivery_timeout")
					reason = it.reason
				}
			assertThat(Duration.ofNanos(System.nanoTime() - started)).isBetween(Duration.ofMillis(1500), Duration.ofMillis(7500))
			// timeout 은 미발행 확정이 아니다 — outcome 은 ack 를 확인하지 못했다는 failed 다.
			assertThat(registry.get("pulsemetry.gateway.publish").tags("signal", "logs", "outcome", "failed", "reason", reason).counter().count()).isEqualTo(1.0)
			assertThat(registry.get("pulsemetry.gateway.publish.duration").tags("outcome", "failed").timer().count()).isEqualTo(1)
		}
	}

	@Test
	@DisplayName("producer 를 닫으면 client meter 가 해제된다 — 다시 만들어도 이전 meter 가 남지 않는다")
	fun producerMetersFollowLifecycle() {
		val registry = SimpleMeterRegistry()
		val props = properties(TelemetryGatewayProperties.Kafka(bootstrapServers = "127.0.0.1:1"))
		fun producerMeters() = registry.meters.filter { it.id.name.startsWith("kafka.producer.") }

		// meter 수는 bind 시점에 producer 가 낸 metric 에 따라 달라진다. 닫은 뒤 하나도 남지 않는 것이 계약이다.
		repeat(2) {
			val producer = KafkaProducer(GatewayConfig.producerProperties(props), StringSerializer(), ByteArraySerializer())
			val binder = KafkaClientMetrics(producer).also { it.bindTo(registry) }
			KafkaTelemetryPublisher(producer, "t", Clock.systemUTC(), Duration.ZERO, clientMetrics = binder).use {
				assertThat(producerMeters()).isNotEmpty()
			}
			assertThat(producerMeters()).isEmpty()
		}
	}

	@Test
	@DisplayName("기한이 이미 지났으면 보내지 않고 deadline_exceeded 다")
	fun pastDeadline() {
		val props = properties(TelemetryGatewayProperties.Kafka(bootstrapServers = "127.0.0.1:1"))
		val registry = SimpleMeterRegistry()
		KafkaTelemetryPublisher(
			KafkaProducer(GatewayConfig.producerProperties(props), StringSerializer(), ByteArraySerializer()),
			"t", Clock.systemUTC(), Duration.ZERO, observer = MicrometerTelemetryObserver(registry),
		).use { publisher ->
			assertThatThrownBy { publisher.publish(message(), Instant.now().minusMillis(1)) }
				.isInstanceOfSatisfying(TelemetryPublishException::class.java) { assertThat(it.reason).isEqualTo("deadline_exceeded") }
		}
		assertThat(registry.get("pulsemetry.gateway.publish").tags("outcome", "failed", "reason", "deadline_exceeded").counter().count()).isEqualTo(1.0)
	}

	@Test
	@DisplayName("실패한 발행 span 은 ERROR status 이고 분류는 reason 코드뿐이다 — 설명과 예외 메시지가 없다(ADR 0069)")
	fun failedPublishSpanIsError() {
		val exporter = InMemorySpanExporter.create()
		val provider = SdkTracerProvider.builder().setSampler(Sampler.alwaysOn()).addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()
		val props = properties(TelemetryGatewayProperties.Kafka(bootstrapServers = "127.0.0.1:1"))
		provider.use {
			KafkaTelemetryPublisher(
				KafkaProducer(GatewayConfig.producerProperties(props), StringSerializer(), ByteArraySerializer()),
				"t", Clock.systemUTC(), Duration.ZERO, tracer = OtelTracer(provider.get("test"), OtelCurrentTraceContext()) { },
			).use { publisher ->
				assertThatThrownBy { publisher.publish(message(), Instant.now().minusMillis(1)) }.isInstanceOf(TelemetryPublishException::class.java)
			}
			val span = exporter.finishedSpanItems.single { it.kind == SpanKind.PRODUCER }
			assertThat(span.status.statusCode).isEqualTo(StatusCode.ERROR)
			assertThat(span.status.description).isEmpty()
			assertThat(span.attributes.get(AttributeKey.stringKey("error.type"))).isEqualTo("deadline_exceeded")
			assertThat(span.events.map { it.attributes.get(AttributeKey.stringKey("exception.message")) }).containsOnlyNulls()
		}
	}

	@Test
	@DisplayName("mTLS 파일 경로를 바인딩하고 기존 Kafka 처리 설정과 함께 SSL client를 만든다")
	fun mtlsSettings(@TempDir directory: Path) {
		val files = KafkaTlsTestFiles.create(directory)
		val source = MapConfigurationPropertySource(mapOf(
			"pulsemetry.telemetry.kafka.security" to "mtls",
			"pulsemetry.telemetry.kafka.bootstrap-servers" to "localhost:9093",
			"pulsemetry.telemetry.kafka.tls.truststore-location" to files.tls.truststoreLocation,
			"pulsemetry.telemetry.kafka.tls.truststore-password-file" to files.tls.truststorePasswordFile,
			"pulsemetry.telemetry.kafka.tls.keystore-location" to files.tls.keystoreLocation,
			"pulsemetry.telemetry.kafka.tls.keystore-password-file" to files.tls.keystorePasswordFile,
		))
		val kafka = Binder(source).bind("pulsemetry.telemetry.kafka", TelemetryGatewayProperties.Kafka::class.java).get()
		assertThat(kafka.security).isEqualTo(TelemetryKafkaSecurity.MTLS)
		assertThat(kafka.tls).isEqualTo(files.tls)
		val props = GatewayConfig.producerProperties(properties(kafka))
		assertThat(props).containsEntry("acks", "all").containsEntry("enable.idempotence", true)
		assertThat(props).containsEntry("security.protocol", "SSL").doesNotContainKey("sasl.mechanism")
		KafkaProducer(props, StringSerializer(), ByteArraySerializer()).close(Duration.ZERO)
	}
}
