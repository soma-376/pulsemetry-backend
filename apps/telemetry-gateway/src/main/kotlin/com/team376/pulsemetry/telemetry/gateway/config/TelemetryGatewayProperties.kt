package com.team376.pulsemetry.telemetry.gateway.config

import com.team376.pulsemetry.telemetry.ingress.TelemetryKafkaConnection
import com.team376.pulsemetry.telemetry.ingress.TelemetryKafkaSecurity
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import java.time.Duration

/**
 * Gateway 의 설정 표면(ADR 0066). 키·기본값·검증의 표는 `docs/telemetry-kafka-operations.md` 에 있다.
 *
 * 기동 때 검증한다 — 공개키가 없거나, 시간 예산이 데몬의 요청 제한(10초) 안의 단일 기한과 맞지 않으면 뜨지 않는다.
 * 라이브러리는 `@ConfigurationProperties` 를 두지 않는다(ADR 0011). 값을 읽어 생성자로 넘기는 것이 이 클래스의 일이다.
 */
@ConfigurationProperties(prefix = "pulsemetry")
data class TelemetryGatewayProperties(
	val telemetryJwt: TelemetryJwt = TelemetryJwt(),
	val telemetry: Telemetry = Telemetry(),
) {

	/**
	 * `kid → X.509 PEM 경로` 맵. **필수다** — Gateway 의 인증은 이 공개키뿐이다. 배포는 `SPRING_APPLICATION_JSON` 으로 넣는다
	 * (환경변수 이름은 kid 의 대소문자를 잃는다). 개인키는 받지 않는다.
	 */
	data class TelemetryJwt(
		val publicKeyFiles: Map<String, String> = emptyMap(),
	)

	data class Telemetry(
		val kafka: Kafka = Kafka(),
		val gateway: Gateway = Gateway(),
	)

	data class Kafka(
		val bootstrapServers: String = "localhost:9092",
		val security: TelemetryKafkaSecurity = TelemetryKafkaSecurity.PLAINTEXT,
		/** ingress topic. dev 는 `pulsemetry-dev-telemetry-ingress-<stage>` 다(infra 가 공급). */
		val topic: String = "pulsemetry-local-telemetry-ingress",
		val iam: Iam = Iam(),
		val producer: Producer = Producer(),
	) {
		init {
			require(topic.isNotBlank()) { "pulsemetry.telemetry.kafka.topic(PULSEMETRY_KAFKA_TOPIC)이 비어 있다" }
		}

		fun connection(): TelemetryKafkaConnection = TelemetryKafkaConnection(
			bootstrapServers = bootstrapServers,
			security = security,
			iamRoleArn = iam.roleArn,
			iamSessionName = iam.sessionName,
			iamStsRegion = iam.stsRegion,
		)
	}

	data class Iam(
		/** workload 전용 MSK 역할. `security=iam` 에서만 쓴다. */
		val roleArn: String = "",
		/** 고정 세션 이름. Pod Identity 자격이 갱신돼도 바뀌지 않아야 MSK 재인증이 통과한다. */
		val sessionName: String = "",
		val stsRegion: String = TelemetryKafkaConnection.DEFAULT_STS_REGION,
	)

	/**
	 * producer 시간·크기 예산(ADR 0066 Decision 3·8). `send()` 는 최대 [maxBlock] 동안 metadata·버퍼를 기다리고, 발행 결과는
	 * [deliveryTimeout] 안에 정해진다. 둘을 더한 값이 HTTP 기한 안이어야 데몬의 10초 안에 503 이라도 돌려준다.
	 */
	data class Producer(
		val maxBlock: Duration = Duration.ofMillis(2000),
		val requestTimeout: Duration = Duration.ofMillis(4000),
		val deliveryTimeout: Duration = Duration.ofMillis(5000),
		val bufferMemory: DataSize = DataSize.ofMegabytes(64),
		val maxRequestSize: DataSize = DataSize.ofMegabytes(12),
		/** 종료 때 남은 발행을 기다리는 상한. Gateway 의 종료 단계(30초) 안이다. */
		val closeTimeout: Duration = Duration.ofSeconds(10),
	) {
		init {
			require(deliveryTimeout >= requestTimeout) {
				"producer delivery-timeout 은 request-timeout 이상이어야 한다(kafka-clients 규칙: delivery ≥ linger + request)"
			}
		}
	}

	data class Gateway(
		/** 압축을 풀기 **전** 원본 바디 상한. 넘으면 413 이다 — ingest 의 기존 값과 같다. */
		val maxRequestBytes: Long = 10L * 1024 * 1024,
		/** 요청 시작부터 응답까지의 단일 기한. 넘으면 실제 발행 여부와 무관하게 503 이다. 데몬은 요청 하나에 10초를 기다린다. */
		val requestDeadline: Duration = Duration.ofMillis(7500),
		/** 503 에 실어 보낼 값. 데몬은 이것을 하한으로 쓰고 15초에서 자른다 (허브 ADR 0006). */
		val retryAfter: Duration = Duration.ofSeconds(1),
	) {
		init {
			require(maxRequestBytes in 1 until Int.MAX_VALUE) {
				"pulsemetry.telemetry.gateway.max-request-bytes 는 1 이상 ${Int.MAX_VALUE - 1} 이하여야 한다."
			}
			require(requestDeadline < Duration.ofSeconds(10)) {
				"pulsemetry.telemetry.gateway.request-deadline 은 데몬의 요청 제한(10초)보다 짧아야 한다."
			}
		}
	}

	init {
		require(telemetryJwt.publicKeyFiles.isNotEmpty()) {
			"pulsemetry.telemetry-jwt.public-key-files 가 비어 있다. Gateway 는 telemetry JWT 공개키 없이 뜨지 않는다."
		}
		val producer = telemetry.kafka.producer
		require(telemetry.gateway.requestDeadline >= producer.maxBlock.plus(producer.deliveryTimeout)) {
			"pulsemetry.telemetry.gateway.request-deadline 은 producer max-block + delivery-timeout 이상이어야 한다 — " +
				"그래야 발행 결과가 기한 안에 정해진다."
		}
		require(producer.maxRequestSize.toBytes() > telemetry.gateway.maxRequestBytes) {
			"producer max-request-size 는 HTTP 본문 상한보다 커야 한다 — envelope 머리를 담을 여유가 필요하다."
		}
	}
}
