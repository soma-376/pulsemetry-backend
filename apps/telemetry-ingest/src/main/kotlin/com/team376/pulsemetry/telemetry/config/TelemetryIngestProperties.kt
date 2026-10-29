package com.team376.pulsemetry.telemetry.config

import com.team376.pulsemetry.persistence.telemetry.ClickHouseHttpClient
import com.team376.pulsemetry.telemetry.collector.OtlpIngestProcessor
import com.team376.pulsemetry.telemetry.adapter.observation.pricing.CodeburnReferencePrices
import com.team376.pulsemetry.telemetry.ingress.TelemetryKafkaConnection
import com.team376.pulsemetry.telemetry.ingress.TelemetryKafkaSecurity
import com.team376.pulsemetry.telemetry.ingress.TelemetryKafkaTls
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import java.time.Duration

/**
 * 이 앱의 설정 표면.
 *
 * 접두사가 `pulsemetry` 인 것은 [tokenHashSecret] 때문이다 — `:apps:enrollment-api` 가 발급할 때
 * 쓰는 키와 **같은 값**이어야 하고, 배포는 환경변수 하나(`PULSEMETRY_TOKEN_HASH_SECRET`)를
 * 두 앱에 똑같이 넣는다. 두 앱이 같은 접두사를 각자 바인딩하는 대가는 ADR 0011 Negative 가
 * 이미 받아들였다 — 갈리면 안 되는 것은 값이 아니라 연산이고, 그쪽은 `TelemetryTokenHasher`
 * 한 벌이 소유한다. 바인딩하지 않은 키는 무시되므로 필요한 것만 담는다.
 *
 * 라이브러리는 `@ConfigurationProperties` 를 두지 않는다(ADR 0011). 값을 읽어 생성자로 넘기는
 * 것이 이 클래스의 일이다.
 */
@ConfigurationProperties(prefix = "pulsemetry")
data class TelemetryIngestProperties(

	/**
	 * telemetry token HMAC-SHA256 키. 비어 있으면 `TelemetryTokenHasher` 생성자가 막아
	 * 애플리케이션이 뜨지 않는다 — **여기서 다시 검증하지 않는다.** 규약은 라이브러리가 소유한다.
	 */
	val tokenHashSecret: String,

	val telemetry: Telemetry = Telemetry(),

	/** telemetry JWT 검증(ADR 0065). 비어 있으면 HTTP ingest 는 `ptt_` 만 받는다. */
	val telemetryJwt: TelemetryJwt = TelemetryJwt(),
) {

	/**
	 * `kid → X.509 PEM 경로` 맵(`pulsemetry.telemetry-jwt.public-key-files`). 배포는 `SPRING_APPLICATION_JSON` 으로 넣는다 —
	 * 환경변수 이름은 kid 의 대소문자를 잃는다. 개인키는 받지 않는다(enrollment 전용).
	 */
	data class TelemetryJwt(
		val publicKeyFiles: Map<String, String> = emptyMap(),
	)

	data class Telemetry(
		val archive: Archive = Archive(),
		val clickhouse: ClickHouse = ClickHouse(),
		val ingest: Ingest = Ingest(),
		val kafka: Kafka = Kafka(),
	)

	/**
	 * Kafka Consumer 설정(ADR 0066 Decision 6·9). 접속 값은 Gateway 와 같은 키다(`PULSEMETRY_KAFKA_*`).
	 * `mode=http` 에서는 빈은 만들되 listener 를 시작하지 않는다 — 접속도 하지 않는다.
	 */
	data class Kafka(
		val bootstrapServers: String = "localhost:9092",
		val security: TelemetryKafkaSecurity = TelemetryKafkaSecurity.PLAINTEXT,
		/** ingress topic. dev 는 `pulsemetry-dev-telemetry-ingress-<stage>` 다(infra 가 공급). */
		val topic: String = "pulsemetry-local-telemetry-ingress",
		/** consumer group. dev 는 `pulsemetry-dev-telemetry-ingest-<stage>` 다. 저장 대상이 다르면 group 도 다르다. */
		val consumerGroup: String = "pulsemetry-local-telemetry-ingest",
		val iam: Iam = Iam(),
		val tls: TelemetryKafkaTls = TelemetryKafkaTls(),
		val consumer: Consumer = Consumer(),
	) {
		init {
			require(topic.isNotBlank()) { "pulsemetry.telemetry.kafka.topic(PULSEMETRY_KAFKA_TOPIC)이 비어 있다" }
			require(consumerGroup.isNotBlank()) { "pulsemetry.telemetry.kafka.consumer-group(PULSEMETRY_KAFKA_CONSUMER_GROUP)이 비어 있다" }
		}

		fun connection(): TelemetryKafkaConnection = TelemetryKafkaConnection(
			bootstrapServers = bootstrapServers,
			security = security,
			iamRoleArn = iam.roleArn,
			iamSessionName = iam.sessionName,
			iamStsRegion = iam.stsRegion,
			tls = tls,
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
	 * 재시도·poll·종료 예산(ADR 0066 Decision 6). 최초 시도 뒤 [retryIntervals] 의 간격으로 재시도하고 소진하면 offset 을 확정한다.
	 *
	 * 한 번의 poll 사이에는 최대 [maxPollRecords] 건의 처리와 재시도 대기 하나가 들어간다 — 그 합이 [maxPollInterval] 보다 짧아야
	 * 그룹에서 밀려나지 않는다. heartbeat 는 백그라운드 스레드가 보내므로 대기 중에도 세션은 유지된다.
	 */
	data class Consumer(
		val retryIntervals: List<Duration> = listOf(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30)),
		val maxPollRecords: Int = 5,
		val maxPollInterval: Duration = Duration.ofSeconds(600),
		/** 브로커·topic 의 message 상한(13 MiB)과 같다 — 12 MiB record 에 배치 머리 여유를 둔다. */
		val fetchMaxBytes: DataSize = DataSize.ofMegabytes(13),
		/** 컨테이너가 처리 중 record 를 끝내고 멈추기를 기다리는 상한. Pod 종료 유예(90초) 안이다. */
		val shutdownTimeout: Duration = Duration.ofSeconds(45),
		/** MSK 인증·인가 실패에서 컨테이너를 멈추지 않고 다시 시도하는 간격. */
		val authExceptionRetryInterval: Duration = Duration.ofSeconds(10),
		/** listener 스레드 수. partition 안의 순서는 스레드 수와 무관하게 유지된다. 메모리 예산이 이 값에 비례한다. */
		val concurrency: Int = 1,
	) {
		init {
			require(retryIntervals.isNotEmpty() && retryIntervals.all { !it.isNegative && !it.isZero }) {
				"pulsemetry.telemetry.kafka.consumer.retry-intervals 는 양의 간격 목록이어야 한다"
			}
			require(maxPollRecords >= 1) { "pulsemetry.telemetry.kafka.consumer.max-poll-records 는 1 이상이어야 한다" }
			require(concurrency >= 1) { "pulsemetry.telemetry.kafka.consumer.concurrency 는 1 이상이어야 한다" }
			require(maxPollInterval > retryIntervals.max()) {
				"pulsemetry.telemetry.kafka.consumer.max-poll-interval 은 가장 긴 재시도 간격보다 길어야 한다"
			}
		}
	}

	/**
	 * 마스킹 이후 원본을 어디에 쓰는가 (ADR 0012). 배포는 `s3`, 로컬 dev 와 테스트는 `file` 이다.
	 */
	data class Archive(
		val type: Type = Type.file,
		/** `file` 일 때 쓰는 루트. `<root>/<제품>/<시그널>.jsonl` 로 append 한다. */
		val dir: String = "./archive",
		val bucket: String = "",
		val basePrefix: String = "",
	) {
		enum class Type { file, s3 }

		init {
			// 빈 버킷으로 조용히 쓰면 복구 원천이 사라진다. 기동에서 끊는다.
			require(type != Type.s3 || bucket.isNotBlank()) {
				"pulsemetry.telemetry.archive.bucket 이 비어 있다. type=s3 에는 버킷이 필요하다."
			}
		}
	}

	data class ClickHouse(
		val url: String = "http://localhost:8123",
		val database: String = ClickHouseHttpClient.DEFAULT_DATABASE,
		val timeout: Duration = ClickHouseHttpClient.DEFAULT_TIMEOUT,
		val schema: Schema = Schema(),
	) {
		init {
			require(url.isNotBlank()) { "pulsemetry.telemetry.clickhouse.url 이 비어 있다." }
		}

		/**
		 * 기동 시 스키마 적용 정책 (ADR 0015 · 0016). 실패해도 애플리케이션은 뜬다 —
		 * 아카이브가 다음 단계보다 앞이라, 앱이 살아 있으면 원본은 외부 저장소에 남는다.
		 */
		data class Schema(
			val startupAttempts: Int = 5,
			val startupBackoff: Duration = Duration.ofSeconds(2),
		)
	}

	data class Ingest(
		/**
		 * 수신 모드(ADR 0066 Decision 7). `http`(기본) — 기존 OTLP/HTTP + JWT 호환, `dual` — HTTP + Kafka Consumer,
		 * `kafka` — Consumer 와 health 만(OTLP 경로는 404). 모르는 값이면 기동이 실패한다.
		 */
		val mode: IngestMode = IngestMode.HTTP,
		/** 불변 참조 환산 프로파일 ID. 명시적으로 none을 선택하면 가격을 계산하지 않는다. */
		val pricingProfile: String = CodeburnReferencePrices.VERSION,
		/** 압축을 푼 뒤의 상한. 넘으면 수집 모듈이 400 을 낸다. */
		val maxDecompressedBytes: Long = OtlpIngestProcessor.DEFAULT_MAX_DECOMPRESSED_BYTES,
		/**
		 * 압축을 풀기 **전** 원본 바디의 상한. 넘으면 413 이다 — 구 auth-proxy 의
		 * `MAX_OTLP_BODY_SIZE` 를 물려받은 값이다.
		 */
		val maxRequestBytes: Long = 10L * 1024 * 1024,
		/**
		 * 503 에 실어 보낼 값. 데몬은 이것을 **하한**으로 쓰고(자기 백오프와 max 를 취한다)
		 * 15초에서 자른다. 크게 잡으면 재시도 예산 셋을 기다림으로 태우므로 짧게 둔다.
		 */
		val retryAfter: Duration = Duration.ofSeconds(1),
	) {
		init {
			// 컨트롤러가 상한 + 1 바이트를 Int 로 읽는다. 넘으면 첫 요청에서 500 이 나므로 기동에서 끊는다.
			require(maxRequestBytes in 1 until Int.MAX_VALUE) {
				"pulsemetry.telemetry.ingest.max-request-bytes 는 1 이상 ${Int.MAX_VALUE - 1} 이하여야 한다."
			}
		}
	}
}
