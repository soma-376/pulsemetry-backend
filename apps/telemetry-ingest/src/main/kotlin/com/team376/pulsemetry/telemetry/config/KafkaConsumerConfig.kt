package com.team376.pulsemetry.telemetry.config

import com.team376.pulsemetry.telemetry.collector.OtlpProcessor
import com.team376.pulsemetry.telemetry.consumer.ProcessTelemetry
import com.team376.pulsemetry.telemetry.consumer.TelemetryFailureReporter
import com.team376.pulsemetry.telemetry.consumer.TelemetryRecordListener
import com.team376.pulsemetry.telemetry.consumer.TelemetryRetryBackOff
import com.team376.pulsemetry.telemetry.credential.TelemetryCredentialAuthorizer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.DefaultErrorHandler

/**
 * Kafka Consumer 의 조립(ADR 0066 Decision 6·7). Boot 의 Kafka 자동설정은 쓰지 않는다 — 접속 방식(plaintext·IAM)과 모드가
 * 설정마다 달라 컨테이너를 명시 빈으로 만든다(ADR 0066 Alternative E). `@KafkaListener` 도 쓰지 않는다.
 *
 * ## 정한 것
 *
 * - bytes 로 받는다(`ByteArrayDeserializer`). envelope 해석은 listener 안의 codec 이다 — 역직렬화 실패도 영구 실패 정책을 탄다.
 * - auto commit 끔, `AckMode.RECORD` — listener 의 정상 반환 하나가 offset 하나다. commit 은 동기다.
 * - `DefaultErrorHandler` + 1·5·30초 backoff(기본 sleep 처리기) + `resetStateOnExceptionChange=false` — 최초 포함 4회.
 *   소진하면 recoverer 가 로그를 남기고 정상 반환하며 오류 처리기가 그 offset 을 확정한다(`ackAfterHandle` 기본값).
 * - `stopImmediate=true` · 종료 45초 — SIGTERM 에 처리 중 record 만 끝내고 멈춘다. 끝내지 못한 record 는 확정하지 않는다.
 * - 인증·인가 예외(MSK IAM)에 컨테이너를 멈추지 않고 10초마다 다시 시도한다.
 * - `deliveryAttemptHeader=true` — listener 가 시도 횟수를 로그에 남긴다.
 * - 소비자는 topic 을 만들지 않는다(`allow.auto.create.topics=false`). 새 group 은 보존된 가장 이른 record 부터 읽는다.
 *
 * `mode=http` 에서는 컨테이너를 시작하지 않는다(`autoStartup=false`) — 빈만 있고 broker 에 붙지 않는다.
 */
@Configuration(proxyBeanMethods = false)
class KafkaConsumerConfig {

	@Bean
	fun processTelemetry(authorizer: TelemetryCredentialAuthorizer, processor: OtlpProcessor): ProcessTelemetry =
		ProcessTelemetry(authorizer, processor)

	@Bean
	fun telemetryConsumerFactory(properties: TelemetryIngestProperties): DefaultKafkaConsumerFactory<ByteArray, ByteArray> =
		DefaultKafkaConsumerFactory(consumerProperties(properties), ByteArrayDeserializer(), ByteArrayDeserializer())

	@Bean
	fun telemetryFailureReporter(properties: TelemetryIngestProperties): TelemetryFailureReporter =
		TelemetryFailureReporter(maxAttempts = properties.telemetry.kafka.consumer.retryIntervals.size + 1)

	@Bean
	fun telemetryErrorHandler(reporter: TelemetryFailureReporter, properties: TelemetryIngestProperties): DefaultErrorHandler =
		DefaultErrorHandler(reporter.recoverer, TelemetryRetryBackOff(properties.telemetry.kafka.consumer.retryIntervals)).apply {
			// 예외 종류가 바뀌어도 예산을 새로 시작하지 않는다 — 4회는 한 처리 흐름의 예산이다.
			setResetStateOnExceptionChange(false)
			setRetryListeners(reporter)
		}

	@Bean
	fun telemetryListenerContainer(
		consumerFactory: DefaultKafkaConsumerFactory<ByteArray, ByteArray>,
		process: ProcessTelemetry,
		errorHandler: DefaultErrorHandler,
		properties: TelemetryIngestProperties,
	): ConcurrentMessageListenerContainer<ByteArray, ByteArray> {
		val kafka = properties.telemetry.kafka
		val consumer = kafka.consumer
		val containerProperties = ContainerProperties(kafka.topic).apply {
			setAckMode(ContainerProperties.AckMode.RECORD)
			setMessageListener(TelemetryRecordListener(process))
			setStopImmediate(true)
			setShutdownTimeout(consumer.shutdownTimeout.toMillis())
			setAuthExceptionRetryInterval(consumer.authExceptionRetryInterval)
			setDeliveryAttemptHeader(true)
			setSyncCommits(true)
			setMissingTopicsFatal(false)
		}
		val mode = properties.telemetry.ingest.mode
		log.info("telemetry ingress consumer: mode={} {} topic={} group={}", mode, kafka.connection(), kafka.topic, kafka.consumerGroup)
		return ConcurrentMessageListenerContainer(consumerFactory, containerProperties).apply {
			concurrency = consumer.concurrency
			setCommonErrorHandler(errorHandler)
			isAutoStartup = mode.kafkaEnabled
			setBeanName(CONTAINER_BEAN)
		}
	}

	companion object {
		private val log = LoggerFactory.getLogger(KafkaConsumerConfig::class.java)

		const val CONTAINER_BEAN: String = "telemetryListenerContainer"
		const val CLIENT_ID: String = "pulsemetry-telemetry-ingest"

		fun consumerProperties(properties: TelemetryIngestProperties): Map<String, Any> {
			val kafka = properties.telemetry.kafka
			val consumer = kafka.consumer
			val fetchMaxBytes = Math.toIntExact(consumer.fetchMaxBytes.toBytes())
			return kafka.connection().clientProperties(CLIENT_ID) + mapOf(
				ConsumerConfig.GROUP_ID_CONFIG to kafka.consumerGroup,
				ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
				ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
				ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG to false,
				ConsumerConfig.MAX_POLL_RECORDS_CONFIG to consumer.maxPollRecords,
				ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG to Math.toIntExact(consumer.maxPollInterval.toMillis()),
				ConsumerConfig.FETCH_MAX_BYTES_CONFIG to fetchMaxBytes,
				ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG to fetchMaxBytes,
			)
		}
	}
}
