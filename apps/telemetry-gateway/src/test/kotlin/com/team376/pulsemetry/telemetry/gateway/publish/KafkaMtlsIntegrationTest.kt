package com.team376.pulsemetry.telemetry.gateway.publish

import com.github.dockerjava.api.command.InspectContainerResponse
import com.team376.pulsemetry.telemetry.gateway.config.GatewayConfig
import com.team376.pulsemetry.telemetry.gateway.config.TelemetryGatewayProperties
import com.team376.pulsemetry.telemetry.ingress.AcceptedTelemetry
import com.team376.pulsemetry.telemetry.ingress.GatewayAuthContext
import com.team376.pulsemetry.telemetry.ingress.KafkaTlsTestFiles
import com.team376.pulsemetry.telemetry.ingress.RawOtlpPayload
import com.team376.pulsemetry.telemetry.ingress.TelemetryEnvelopeCodec
import com.team376.pulsemetry.telemetry.ingress.TelemetryKafkaConnection
import com.team376.pulsemetry.telemetry.ingress.TelemetryKafkaSecurity
import com.team376.pulsemetry.telemetry.ingress.TelemetryKafkaTls
import com.team376.pulsemetry.telemetry.ingress.TelemetryRecordKey
import com.team376.pulsemetry.telemetry.otlp.OtlpMediaType
import com.team376.pulsemetry.telemetry.otlp.OtlpSignal
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.SslAuthenticationException
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.MountableFile
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/** dev와 같은 broker image에서 접속 계약을 검증한다. 3 broker ISR/ACL/배포 인수는 infra smoke가 맡는다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension::class)
@Timeout(90)
class KafkaMtlsIntegrationTest {

	private lateinit var broker: MtlsBroker
	private lateinit var client: TelemetryKafkaTls
	private lateinit var untrusted: TelemetryKafkaTls

	@BeforeAll
	fun start(@TempDir directory: Path) {
		val server = KafkaTlsTestFiles.create(directory.resolve("server"))
		val user = KafkaTlsTestFiles.create(directory.resolve("user"))
		untrusted = KafkaTlsTestFiles.create(directory.resolve("untrusted")).tls
		client = user.tls.copy(
			truststoreLocation = server.tls.truststoreLocation,
			truststorePasswordFile = server.tls.truststorePasswordFile,
		)
		broker = MtlsBroker(server.tls, user.tls).also { it.start() }
		AdminClient.create(connection()).use {
			it.createTopics(listOf(NewTopic(TOPIC, 1, 1.toShort()))).all().get(15, TimeUnit.SECONDS)
		}
	}

	@AfterAll
	fun stop() {
		if (::broker.isInitialized) broker.stop()
	}

	@Test
	@DisplayName("Strimzi Kafka 4.2.2 mTLS에서 Gateway ACK·envelope 수신·명시 offset commit을 확인하고 비밀번호를 로그에 남기지 않는다")
	fun publishReceiveCommit(output: CapturedOutput) {
		val message = AcceptedTelemetry(
			UUID.randomUUID(), Instant.now(),
			GatewayAuthContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "pulsemetry-enrollment", "k", Instant.now(), Instant.now().plusSeconds(60)),
			RawOtlpPayload(OtlpSignal.LOGS, OtlpMediaType.JSON, "", "{}".toByteArray()),
		)
		val properties = TelemetryGatewayProperties(
			telemetryJwt = TelemetryGatewayProperties.TelemetryJwt(mapOf("k" to "/unused")),
			telemetry = TelemetryGatewayProperties.Telemetry(kafka = TelemetryGatewayProperties.Kafka(
				bootstrapServers = broker.bootstrapServers, security = TelemetryKafkaSecurity.MTLS, tls = client,
			)),
		)
		val published = KafkaTelemetryPublisher(
			KafkaProducer(GatewayConfig.producerProperties(properties), StringSerializer(), ByteArraySerializer()),
			TOPIC, Clock.systemUTC(), Duration.ofSeconds(5),
		).use { it.publish(message, Instant.now().plusSeconds(15)) }
		assertThat(published.offset).isGreaterThanOrEqualTo(0)
		val partition = TopicPartition(TOPIC, published.partition)
		val group = "mtls-${UUID.randomUUID()}"
		KafkaConsumer(connection() + mapOf("group.id" to group, "enable.auto.commit" to false), StringDeserializer(), ByteArrayDeserializer()).use { consumer ->
			consumer.assign(listOf(partition))
			consumer.seek(partition, published.offset)
			val until = System.nanoTime() + Duration.ofSeconds(15).toNanos()
			var received = false
			while (!received && System.nanoTime() < until) {
				consumer.poll(Duration.ofMillis(500)).forEach { record ->
					assertThat(record.key()).isEqualTo(TelemetryRecordKey.of(message.auth.tenantId, message.auth.installationId))
					assertThat(record.value()).isEqualTo(TelemetryEnvelopeCodec.encode(message))
					received = true
				}
			}
			assertThat(received).isTrue()
			assertThat(consumer.committed(setOf(partition))[partition]).isNull()
			consumer.commitSync(mapOf(partition to OffsetAndMetadata(published.offset + 1)))
			assertThat(consumer.committed(setOf(partition)).getValue(partition).offset()).isEqualTo(published.offset + 1)
		}
		assertThat(output.all).doesNotContain(KafkaTlsTestFiles.PASSWORD, "BEGIN PRIVATE KEY")
	}

	@Test
	@DisplayName("다른 CA를 신뢰하는 client는 브로커 TLS를 거부한다")
	fun wrongCa() {
		assertAuthenticationFails(connection(client.copy(truststoreLocation = untrusted.truststoreLocation)))
	}

	@Test
	@DisplayName("사용자 인증서가 없는 client는 mTLS 브로커에 접속하지 못한다")
	fun noClientCertificate() {
		assertAuthenticationFails(connection().filterKeys { !it.startsWith("ssl.keystore.") && it != "ssl.key.password" })
	}

	private fun connection(tls: TelemetryKafkaTls = client): Map<String, Any> =
		TelemetryKafkaConnection(broker.bootstrapServers, TelemetryKafkaSecurity.MTLS, tls = tls).clientProperties("mtls-test")

	private fun assertAuthenticationFails(properties: Map<String, Any>) {
		AdminClient.create(properties + mapOf("request.timeout.ms" to 2000, "default.api.timeout.ms" to 5000)).use { admin ->
			assertThatThrownBy { admin.describeCluster().nodes().get(10, TimeUnit.SECONDS) }
				.hasCauseInstanceOf(SslAuthenticationException::class.java)
		}
	}

	/** Strimzi image 자체의 Kafka scripts를 사용한다. 운영 인증서/클러스터에는 접속하지 않는다. */
	private class MtlsBroker(server: TelemetryKafkaTls, user: TelemetryKafkaTls) : GenericContainer<MtlsBroker>(IMAGE) {
		init {
			withExposedPorts(9092)
			withEnv("LOG_DIR", "/tmp/kafka-logs")
			withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx512m")
			withCopyFileToContainer(MountableFile.forHostPath(server.keystoreLocation, 420), "/tmp/server.p12")
			withCopyFileToContainer(MountableFile.forHostPath(user.truststoreLocation, 420), "/tmp/clients.p12")
			withCommand("/bin/bash", "-ec", "while [[ ! -f /tmp/start-kafka.sh ]]; do sleep 0.1; done; exec /bin/bash /tmp/start-kafka.sh")
			waitingFor(Wait.forLogMessage(".*Kafka Server started.*", 1))
			withStartupTimeout(Duration.ofSeconds(90))
		}

		val bootstrapServers: String get() = "$host:${getMappedPort(9092)}"

		override fun containerIsStarting(containerInfo: InspectContainerResponse) {
			val config = """
				process.roles=broker,controller
				node.id=1
				controller.quorum.voters=1@localhost:9093
				controller.listener.names=CONTROLLER
				listeners=SSL://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093,INTERNAL://0.0.0.0:9094
				advertised.listeners=SSL://$bootstrapServers,INTERNAL://localhost:9094
				listener.security.protocol.map=SSL:SSL,CONTROLLER:PLAINTEXT,INTERNAL:PLAINTEXT
				inter.broker.listener.name=INTERNAL
				ssl.keystore.location=/tmp/server.p12
				ssl.keystore.type=PKCS12
				ssl.keystore.password=${KafkaTlsTestFiles.PASSWORD}
				ssl.key.password=${KafkaTlsTestFiles.PASSWORD}
				ssl.truststore.location=/tmp/clients.p12
				ssl.truststore.type=PKCS12
				ssl.truststore.password=${KafkaTlsTestFiles.PASSWORD}
				ssl.client.auth=required
				log.dirs=/tmp/kafka-data
				offsets.topic.replication.factor=1
				transaction.state.log.replication.factor=1
				transaction.state.log.min.isr=1
				group.initial.rebalance.delay.ms=0
				auto.create.topics.enable=false
			""".trimIndent()
			copyFileToContainer(Transferable.of(config, 420), "/tmp/server.properties")
			copyFileToContainer(Transferable.of("""
				#!/bin/bash
				set -e
				/opt/kafka/bin/kafka-storage.sh format -t 4L6g3nShT-eMCtK--X86sw -c /tmp/server.properties
				exec /opt/kafka/bin/kafka-server-start.sh /tmp/server.properties
			""".trimIndent(), 493), "/tmp/start-kafka.sh")
		}
	}

	companion object {
		private const val IMAGE = "quay.io/strimzi/kafka:1.3.0-kafka-4.2.2"
		private const val TOPIC = "pulsemetry-test-mtls"
	}
}
