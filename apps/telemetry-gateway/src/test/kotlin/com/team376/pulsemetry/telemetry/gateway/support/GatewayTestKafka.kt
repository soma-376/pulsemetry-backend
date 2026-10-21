package com.team376.pulsemetry.telemetry.gateway.support

import com.team376.pulsemetry.telemetry.auth.support.TelemetryJwtFixtures
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.testcontainers.kafka.KafkaContainer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Gateway 테스트의 Kafka 와 telemetry JWT 키. 운영 MSK 처럼 topic 자동 생성을 끄고 message 크기를 13 MiB 로 둔다(ADR 0066 Decision 8).
 *
 * 이미지 태그는 MSK 의 Kafka 3.9 계열에 맞춘다. 로컬 Compose 와 ingest 테스트도 같은 태그다.
 */
object GatewayTestKafka {

	const val IMAGE: String = "apache/kafka:3.9.2"
	const val TOPIC: String = "pulsemetry-test-telemetry-ingress"
	const val PARTITIONS: Int = 3
	const val MAX_MESSAGE_BYTES: Int = 13 * 1024 * 1024
	const val KID: String = "tk-gateway"

	val KEY: TelemetryJwtFixtures.SigningKey = TelemetryJwtFixtures.signingKey(KID)
	val PUBLIC_PEM: Path = KEY.writePem(Files.createTempDirectory("gateway-telemetry-jwt")).second

	/** 13 MiB record 를 받는 단일 노드 KRaft broker. 자동 topic 생성을 끈다. */
	fun container(): KafkaContainer = KafkaContainer(IMAGE)
		.withEnv("KAFKA_MESSAGE_MAX_BYTES", MAX_MESSAGE_BYTES.toString())
		.withEnv("KAFKA_REPLICA_FETCH_MAX_BYTES", MAX_MESSAGE_BYTES.toString())
		.withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")

	val shared: KafkaContainer by lazy { container().also { it.start(); createTopic(it.bootstrapServers) } }

	fun createTopic(bootstrap: String, topic: String = TOPIC, partitions: Int = PARTITIONS) {
		AdminClient.create(mapOf<String, Any>(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap)).use { admin ->
			admin.createTopics(
				listOf(
					NewTopic(topic, partitions, 1.toShort())
						.configs(mapOf("max.message.bytes" to MAX_MESSAGE_BYTES.toString())),
				),
			).all().get()
		}
	}

	/** topic 의 처음부터 지금까지의 record 를 읽는다. 테스트마다 다른 tenant 로 걸러 쓴다. */
	fun records(bootstrap: String, minimum: Int = 1, timeout: Duration = Duration.ofSeconds(15)): List<ConsumerRecord<String, ByteArray>> {
		val props = mapOf<String, Any>(
			ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap,
			ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
			ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG to MAX_MESSAGE_BYTES,
			ConsumerConfig.FETCH_MAX_BYTES_CONFIG to MAX_MESSAGE_BYTES,
		)
		KafkaConsumer(props, StringDeserializer(), ByteArrayDeserializer()).use { consumer ->
			val partitions = (0 until PARTITIONS).map { TopicPartition(TOPIC, it) }
			consumer.assign(partitions)
			consumer.seekToBeginning(partitions)
			val records = mutableListOf<ConsumerRecord<String, ByteArray>>()
			val until = System.nanoTime() + timeout.toNanos()
			while (System.nanoTime() < until) {
				consumer.poll(Duration.ofMillis(500)).forEach { records += it }
				val end = consumer.endOffsets(partitions)
				if (records.size >= minimum && partitions.all { consumer.position(it) >= end.getValue(it) }) break
			}
			return records
		}
	}
}
