package com.team376.pulsemetry.telemetry.support

import com.team376.pulsemetry.telemetry.ingress.AcceptedTelemetry
import com.team376.pulsemetry.telemetry.ingress.GatewayAuthContext
import com.team376.pulsemetry.telemetry.ingress.RawOtlpPayload
import com.team376.pulsemetry.telemetry.ingress.TelemetryEnvelopeCodec
import com.team376.pulsemetry.telemetry.ingress.TelemetryRecordKey
import com.team376.pulsemetry.telemetry.otlp.OtlpMediaType
import com.team376.pulsemetry.telemetry.otlp.OtlpSignal
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.testcontainers.kafka.KafkaContainer
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * ingest 통합 테스트의 Kafka(dual 모드, ADR 0066). 운영 MSK 처럼 topic 자동 생성을 끄고 message 크기를 13 MiB 로 둔다.
 * 테스트가 Gateway 대신 envelope 를 직접 발행한다 — 같은 codec 이다.
 */
object IngestTestKafka {

	const val TOPIC: String = "pulsemetry-test-telemetry-ingress"
	const val GROUP: String = "pulsemetry-test-telemetry-ingest"
	const val PARTITIONS: Int = 3
	private const val MAX_MESSAGE_BYTES: Int = 13 * 1024 * 1024

	val container: KafkaContainer = KafkaContainer("apache/kafka:3.9.2")
		.withEnv("KAFKA_MESSAGE_MAX_BYTES", MAX_MESSAGE_BYTES.toString())
		.withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
		.also { it.start() }
		.also { createIngressTopic(it.bootstrapServers) }

	private val producer: KafkaProducer<String, ByteArray> by lazy {
		KafkaProducer(
			mapOf<String, Any>(
				ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to container.bootstrapServers,
				ProducerConfig.ACKS_CONFIG to "all",
				ProducerConfig.MAX_REQUEST_SIZE_CONFIG to 12 * 1024 * 1024,
			),
			StringSerializer(),
			ByteArraySerializer(),
		)
	}

	private fun createIngressTopic(bootstrap: String) {
		admin(bootstrap).use { admin ->
			admin.createTopics(
				listOf(NewTopic(TOPIC, PARTITIONS, 1.toShort()).configs(mapOf("max.message.bytes" to MAX_MESSAGE_BYTES.toString()))),
			).all().get()
		}
	}

	/** 다른 컨텍스트용 topic. 이미 있으면 그대로 둔다. */
	fun createTopic(topic: String) {
		admin().use { admin ->
			if (topic !in admin.listTopics().names().get()) {
				admin.createTopics(listOf(NewTopic(topic, 1, 1.toShort()))).all().get()
			}
		}
	}

	private fun admin(bootstrap: String = container.bootstrapServers): AdminClient =
		AdminClient.create(mapOf<String, Any>(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap))

	/** 시드한 JWT 자격 기록과 같은 인증 문맥으로 envelope 를 만든다 — Gateway 가 검증 뒤 만드는 값과 같다. */
	fun accepted(
		seeded: IngestTestData.Seeded,
		body: ByteArray,
		acceptedAt: Instant = Instant.now(),
		contentEncoding: String = "",
		messageId: UUID = UUID.randomUUID(),
		signal: OtlpSignal = OtlpSignal.LOGS,
		mediaType: OtlpMediaType = OtlpMediaType.JSON,
	) = AcceptedTelemetry(
		messageId = messageId,
		acceptedAt = acceptedAt,
		auth = GatewayAuthContext(
			credentialId = seeded.credentialId!!,
			tenantId = seeded.tenantId,
			installationId = seeded.installationId,
			issuer = "pulsemetry-enrollment",
			keyId = IngestJwtKeys.KID,
			issuedAt = seeded.issuedAt!!,
			expiresAt = seeded.expiresAt!!,
		),
		payload = RawOtlpPayload(signal, mediaType, contentEncoding, body),
	)

	fun publish(message: AcceptedTelemetry): RecordMetadata =
		send(TelemetryRecordKey.of(message.auth.tenantId, message.auth.installationId), TelemetryEnvelopeCodec.encode(message))

	fun send(key: String, value: ByteArray?): RecordMetadata =
		producer.send(ProducerRecord(TOPIC, key, value)).get(10, TimeUnit.SECONDS)

	/** group 이 그 partition 에 확정한 다음 offset. 없으면 -1. */
	fun committed(partition: Int): Long = admin().use { admin ->
		admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS)[TopicPartition(TOPIC, partition)]?.offset() ?: -1
	}
}
