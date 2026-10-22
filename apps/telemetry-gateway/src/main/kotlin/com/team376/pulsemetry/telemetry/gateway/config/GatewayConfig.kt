package com.team376.pulsemetry.telemetry.gateway.config

import com.team376.pulsemetry.telemetry.auth.TelemetryJwtKeys
import com.team376.pulsemetry.telemetry.auth.TelemetryJwtVerifier
import com.team376.pulsemetry.telemetry.gateway.publish.KafkaTelemetryPublisher
import com.team376.pulsemetry.telemetry.observability.TelemetryObserver
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.kafka.KafkaClientMetrics
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * Gateway 의 조립. 라이브러리(`:libs:telemetry-auth`·`:libs:telemetry-ingress-contract`)는 스테레오타입을 달지 않으므로
 * 여기서 엮는다(ADR 0011). Boot 의 Kafka 자동설정은 쓰지 않는다 — producer 하나를 명시 빈으로 만든다(ADR 0066 Alternative E).
 */
@Configuration(proxyBeanMethods = false)
class GatewayConfig {

	@Bean
	fun clock(): Clock = Clock.systemUTC()

	/** 공개키는 기동 때 한 번 읽는다. 파일만 바꿔서는 키가 바뀌지 않는다 — 키 회전은 재기동이다. */
	@Bean
	fun telemetryJwtVerifier(properties: TelemetryGatewayProperties): TelemetryJwtVerifier =
		TelemetryJwtVerifier(TelemetryJwtKeys.publicKeys(properties.telemetryJwt.publicKeyFiles)).also {
			log.info("telemetry JWT 검증 키: kids={}", it.keyIds.sorted())
		}

	/**
	 * producer 는 생성만으로 broker 에 붙지 않는다 — 첫 발행에서 metadata 를 받는다. 그래서 Kafka 가 없어도 기동한다.
	 * 종료는 Spring 이 웹 서버의 graceful 종료를 끝낸 뒤 [KafkaTelemetryPublisher.close] 로 한다.
	 */
	@Bean(destroyMethod = "close")
	fun telemetryPublisher(properties: TelemetryGatewayProperties, clock: Clock, observer: TelemetryObserver, registry: MeterRegistry): KafkaTelemetryPublisher {
		val kafka = properties.telemetry.kafka
		log.info("telemetry ingress producer: {} topic={}", kafka.connection(), kafka.topic)
		val producer = KafkaProducer(producerProperties(properties), StringSerializer(), ByteArraySerializer())
		val clientMetrics = KafkaClientMetrics(producer).also { it.bindTo(registry) }
		return KafkaTelemetryPublisher(
			producer = producer,
			topic = kafka.topic,
			clock = clock,
			closeTimeout = kafka.producer.closeTimeout,
			observer = observer,
			clientMetrics = clientMetrics,
		)
	}

	companion object {
		private val log = LoggerFactory.getLogger(GatewayConfig::class.java)

		const val CLIENT_ID: String = "pulsemetry-telemetry-gateway"

		/**
		 * producer 설정(ADR 0066 Decision 2·3·8). `acks=all`·idempotence 로 broker 의 minISR 까지 기록된 뒤에만 ack 다.
		 * 압축은 하지 않는다 — 본문은 대개 이미 gzip 이다. 테스트가 같은 설정으로 IAM 클래스 로딩을 확인한다.
		 */
		fun producerProperties(properties: TelemetryGatewayProperties): Map<String, Any> {
			val kafka = properties.telemetry.kafka
			val producer = kafka.producer
			return kafka.connection().clientProperties(CLIENT_ID) + mapOf(
				ProducerConfig.ACKS_CONFIG to "all",
				ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true,
				ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION to 5,
				ProducerConfig.LINGER_MS_CONFIG to 5,
				ProducerConfig.COMPRESSION_TYPE_CONFIG to "none",
				ProducerConfig.MAX_BLOCK_MS_CONFIG to producer.maxBlock.toMillis(),
				ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG to producer.requestTimeout.toMillis().toInt(),
				ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG to producer.deliveryTimeout.toMillis().toInt(),
				ProducerConfig.BUFFER_MEMORY_CONFIG to producer.bufferMemory.toBytes(),
				ProducerConfig.MAX_REQUEST_SIZE_CONFIG to producer.maxRequestSize.toBytes().toInt(),
			)
		}
	}
}
