package com.team376.pulsemetry.telemetry.config

import com.team376.pulsemetry.telemetry.ingress.TelemetryKafkaSecurity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import java.time.Duration

/** 모드·재시도·consumer 설정의 기본값과 기동 검증(ADR 0066 Decision 6·7·8·9). Spring 컨텍스트 없이 돈다. */
class IngestKafkaSettingsTest {

	private fun bind(vararg entries: Pair<String, String>): IngestMode = IngestMode.of(Binder(MapConfigurationPropertySource(entries.toMap())))

	@Test
	@DisplayName("모드는 http·dual·kafka 이고 대소문자를 가리지 않으며 기본은 http 다")
	fun modes() {
		assertThat(bind()).isEqualTo(IngestMode.HTTP)
		assertThat(bind(IngestMode.PROPERTY to "dual")).isEqualTo(IngestMode.DUAL)
		assertThat(bind(IngestMode.PROPERTY to "KAFKA")).isEqualTo(IngestMode.KAFKA)
		assertThat(IngestMode.HTTP.httpEnabled && !IngestMode.HTTP.kafkaEnabled).isTrue()
		assertThat(IngestMode.DUAL.httpEnabled && IngestMode.DUAL.kafkaEnabled).isTrue()
		assertThat(!IngestMode.KAFKA.httpEnabled && IngestMode.KAFKA.kafkaEnabled).isTrue()
	}

	@Test
	@DisplayName("모르는 모드는 기동 실패다")
	fun unknownModeFails() {
		assertThatThrownBy { bind(IngestMode.PROPERTY to "batch") }.isInstanceOf(BindException::class.java)
	}

	@Test
	@DisplayName("재시도 간격의 기본은 1·5·30초이고 poll·fetch·종료 예산은 계약값이다")
	fun consumerDefaults() {
		val consumer = TelemetryIngestProperties.Consumer()

		assertThat(consumer.retryIntervals).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30))
		assertThat(consumer.maxPollRecords).isEqualTo(5)
		assertThat(consumer.maxPollInterval).isEqualTo(Duration.ofSeconds(600))
		assertThat(consumer.fetchMaxBytes.toBytes()).isEqualTo(13L * 1024 * 1024)
		assertThat(consumer.shutdownTimeout).isEqualTo(Duration.ofSeconds(45))
		assertThat(TelemetryIngestProperties.Ingest().mode).isEqualTo(IngestMode.HTTP)
	}

	@Test
	@DisplayName("consumer 설정은 auto commit 끔·topic 자동 생성 끔·가장 이른 offset·13 MiB fetch 다")
	fun consumerProperties() {
		val props = KafkaConsumerConfig.consumerProperties(TelemetryIngestProperties("secret"))

		assertThat(props).containsAllEntriesOf(
			mapOf(
				"enable.auto.commit" to false, "allow.auto.create.topics" to false, "auto.offset.reset" to "earliest",
				"max.poll.records" to 5, "max.poll.interval.ms" to 600_000, "fetch.max.bytes" to 13 * 1024 * 1024,
				"max.partition.fetch.bytes" to 13 * 1024 * 1024, "group.id" to "pulsemetry-local-telemetry-ingest",
				"security.protocol" to "PLAINTEXT", "client.id" to "pulsemetry-telemetry-ingest",
			),
		)
	}

	@Test
	@DisplayName("MSK IAM 설정으로 consumer 를 만들 수 있다 — 로그인 모듈·콜백 핸들러 클래스가 런타임에 있다")
	fun iamClassesLoad() {
		val properties = TelemetryIngestProperties(
			tokenHashSecret = "secret",
			telemetry = TelemetryIngestProperties.Telemetry(
				kafka = TelemetryIngestProperties.Kafka(
					bootstrapServers = "localhost:9098",
					security = TelemetryKafkaSecurity.IAM,
					iam = TelemetryIngestProperties.Iam(
						roleArn = "arn:aws:iam::123456789012:role/pulsemetry-dev-telemetry-ingest-msk",
						sessionName = "telemetry-ingest",
					),
				),
			),
		)
		val props = KafkaConsumerConfig.consumerProperties(properties)

		assertThat(props["sasl.mechanism"]).isEqualTo("AWS_MSK_IAM")
		KafkaConsumer(props, ByteArrayDeserializer(), ByteArrayDeserializer()).close(Duration.ZERO)
	}

	@Test
	@DisplayName("poll 간격이 가장 긴 재시도 간격보다 짧으면 기동하지 않는다")
	fun pollIntervalMustCoverBackoff() {
		assertThatThrownBy {
			TelemetryIngestProperties.Consumer(retryIntervals = listOf(Duration.ofSeconds(700)))
		}.hasMessageContaining("max-poll-interval")
		assertThatThrownBy { TelemetryIngestProperties.Consumer(retryIntervals = emptyList()) }.hasMessageContaining("retry-intervals")
	}
}
