package com.team376.pulsemetry.telemetry.config

import com.team376.pulsemetry.persistence.telemetry.ClickHouseHttpClient
import com.team376.pulsemetry.telemetry.collector.OtlpIngestProcessor
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
	) {
		init {
			// 배치 모드의 ClickHouse 요청은 남은 시간이 min-remaining-to-start 일 때도 시작할 수 있고 clickhouse.timeout 까지 걸린다.
			// 그래도 max-poll-interval 안에 끝나야 그룹에서 밀려나 같은 배치를 다시 받지 않는다.
			val consumer = kafka.consumer
			val batch = consumer.batch
			require(!batch.enabled || batch.callDeadline.minus(batch.minRemainingToStart).plus(clickhouse.timeout) < consumer.maxPollInterval) {
				"pulsemetry.telemetry.kafka.consumer.batch.call-deadline - min-remaining-to-start + pulsemetry.telemetry.clickhouse.timeout 은 " +
					"pulsemetry.telemetry.kafka.consumer.max-poll-interval 보다 짧아야 한다"
			}
			// 명시한 S3 연결 수가 배치 소비 스레드 전부의 동시 PUT 보다 적으면 연결을 기다리다(SDK 기본 10초) 아카이브가 실패하고 배치가 긴 대기로 간다.
			val configured = archive.s3MaxConnections
			val required = batchS3Connections(consumer.concurrency, batch.archiveConcurrency)
			require(configured == null || !batch.enabled || configured >= required) {
				"pulsemetry.telemetry.archive.s3-max-connections(PULSEMETRY_ARCHIVE_S3_MAX_CONNECTIONS) 는 배치 모드에서 " +
					"pulsemetry.telemetry.kafka.consumer.concurrency × (pulsemetry.telemetry.kafka.consumer.batch.archive-concurrency + 1) = $required 이상이어야 한다"
			}
		}

		/**
		 * S3 클라이언트의 최대 연결 수(PROJ-347). 원본 아카이브·넘김 목록·HTTP 경로가 이 클라이언트 하나를 함께 쓴다.
		 *
		 * 배치 모드는 수신 모드와 무관하게 `batch.enabled` 로 정한다 — 배치 빈([KafkaBatchConfig])과 위 배치 검증이 같은 기준이다.
		 * `http` 모드에서는 소비 스레드가 돌지 않지만, 계산값은 상한일 뿐 연결을 미리 열지 않는다.
		 */
		fun s3MaxConnections(): Int = resolveS3MaxConnections(
			configured = archive.s3MaxConnections,
			batchEnabled = kafka.consumer.batch.enabled,
			consumers = kafka.consumer.concurrency,
			archiveConcurrency = kafka.consumer.batch.archiveConcurrency,
		)

		companion object {
			/** SDK 동기 HTTP 클라이언트의 기본 최대 연결 수. 계산값의 하한이다 — 배치가 아닌 경로(HTTP·단건 소비)가 지금 쓰는 상한을 줄이지 않는다. */
			const val DEFAULT_S3_MAX_CONNECTIONS: Int = 50

			/** 배치 소비 스레드 전부가 동시에 쓸 수 있는 S3 연결 수. 스레드마다 원본 PUT [archiveConcurrency] 개와 그 스레드가 직접 쓰는 넘김 목록 PUT 하나다. */
			fun batchS3Connections(consumers: Int, archiveConcurrency: Int): Int = Math.multiplyExact(consumers, archiveConcurrency + 1)

			/** 명시값이 있으면 그 값, 없으면 배치 모드에서 `max(50, 소비자 수 × (동시 수 + 1))`, 배치가 꺼져 있으면 50 이다. */
			fun resolveS3MaxConnections(configured: Int?, batchEnabled: Boolean, consumers: Int, archiveConcurrency: Int): Int = when {
				configured != null -> configured
				batchEnabled -> maxOf(DEFAULT_S3_MAX_CONNECTIONS, batchS3Connections(consumers, archiveConcurrency))
				else -> DEFAULT_S3_MAX_CONNECTIONS
			}
		}
	}

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
		/** 배치 소비(PROJ-341 · ADR 0083). 꺼져 있으면 단건 listener(ADR 0066 Decision 6) 그대로다. */
		val batch: Batch = Batch(),
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
			require(!batch.enabled || batch.callDeadline < maxPollInterval) {
				"pulsemetry.telemetry.kafka.consumer.batch.call-deadline 은 max-poll-interval 보다 짧아야 한다"
			}
		}
	}

	/**
	 * Kafka 배치 소비(PROJ-341 · ADR 0083). 켜면 poll 결과를 원본 레코드 단위로 준비한 뒤 tenant·분석 테이블마다 INSERT 하나로 쓰고,
	 * 배치 끝에서 offset 을 확정한다(`AckMode.BATCH`). 단기 재시도 간격은 단건과 같은 `retry-intervals` 다.
	 *
	 * 기본값은 계획서(11차 개정안)의 dev 시작값이다. 팀 결정이 정해지지 않은 두 정책은 [partialDefect]·[singleRecordFailure] 다.
	 */
	data class Batch(
		val enabled: Boolean = false,
		/** 배치 모드의 `max.poll.records`. 단건 모드의 `max-poll-records` 와 따로다. */
		val maxPollRecords: Int = 100,
		/** 브로커가 응답하기 전에 모을 최소 바이트(`fetch.min.bytes`). poll 은 이미 가져온 데이터를 먼저 돌려줄 수 있다. */
		val fetchMinBytes: DataSize = DataSize.ofKilobytes(64),
		/** [fetchMinBytes] 를 채우지 못해도 브로커가 응답하는 대기(`fetch.max.wait.ms`). */
		val fetchMaxWait: Duration = Duration.ofSeconds(1),
		/** INSERT 본문 하나의 상한(미만, UTF-8 바이트·줄바꿈 포함). 한 행만 담은 본문이 이 값 이상이면 그 행은 독립 INSERT 로 보낸다. */
		val maxInsertBytes: Long = 10_000_000,
		/**
		 * 한 배치 안에서 동시에 진행하는 원본 아카이브(S3 PUT) 수. 프로세스 전체가 아니라 **소비 스레드마다**의 상한이다 —
		 * S3 연결은 스레드 수 × (이 값 + 1)까지 쓴다([Telemetry.s3MaxConnections]).
		 */
		val archiveConcurrency: Int = 8,
		/**
		 * 한 리스너 호출의 마감(단조 시계). 마감 뒤에는 새 외부 작업을 시작하지 않는다. 진행 중인 요청의 기한·커밋 여유를 더해도
		 * `max-poll-interval` 안에 들도록 둔다.
		 */
		val callDeadline: Duration = Duration.ofSeconds(420),
		/** 새 외부 작업을 시작하려면 마감까지 남아 있어야 하는 시간 — 요청 하나의 기한과 취소 여유다. */
		val minRemainingToStart: Duration = Duration.ofSeconds(35),
		/** 진척 있는 마감 뒤 다음 호출까지의 짧은 대기. */
		val progressInterval: Duration = Duration.ofMillis(200),
		/** 넘길 수 없는 장애·예상 밖 오류에서 단기 예산을 쓴 뒤의 긴 대기. */
		val longWait: Duration = Duration.ofMinutes(5),
		/** 열린 차단기의 탐침 간격. */
		val breakerProbeInterval: Duration = Duration.ofMinutes(5),
		/** 복구 확인 `SELECT 1` 의 기한. */
		val probeTimeout: Duration = Duration.ofSeconds(5),
		/** 메모리 한도 진단에서 한 호출이 보내는 작은 쓰기 수의 상한. */
		val maxDiagnosticWrites: Int = 32,
		/** 팀 결정 3번(미정) — 확인된 결함이 일부만 적재된 레코드. 기본은 기존 단건 경로와 같은 거부 기록이다. */
		val partialDefect: PartialDefectPolicy = PartialDefectPolicy.REJECT,
		/** 팀 결정 4번(기본 넘김) — 다른 조각은 저장되는데 한 레코드만 반복 실패할 때. */
		val singleRecordFailure: SingleRecordFailurePolicy = SingleRecordFailurePolicy.HANDOFF,
	) {
		init {
			require(maxPollRecords >= 1) { "pulsemetry.telemetry.kafka.consumer.batch.max-poll-records 는 1 이상이어야 한다" }
			require(maxInsertBytes >= 1024) { "pulsemetry.telemetry.kafka.consumer.batch.max-insert-bytes 는 1024 이상이어야 한다" }
			// 상한은 오타(예: 160)를 막는다. 소비 스레드마다 이만큼 S3 연결을 더 쓴다.
			require(archiveConcurrency in 1..MAX_ARCHIVE_CONCURRENCY) {
				"pulsemetry.telemetry.kafka.consumer.batch.archive-concurrency 는 1 이상 $MAX_ARCHIVE_CONCURRENCY 이하여야 한다"
			}
			require(maxDiagnosticWrites >= 2) { "pulsemetry.telemetry.kafka.consumer.batch.max-diagnostic-writes 는 2 이상이어야 한다" }
			listOf(fetchMaxWait, callDeadline, minRemainingToStart, progressInterval, longWait, breakerProbeInterval, probeTimeout).forEach {
				require(!it.isNegative && !it.isZero) { "pulsemetry.telemetry.kafka.consumer.batch 의 시간 설정은 양수여야 한다: $it" }
			}
			require(minRemainingToStart < callDeadline) { "batch.min-remaining-to-start 는 batch.call-deadline 보다 짧아야 한다" }
		}

		enum class PartialDefectPolicy {
			/** 거부를 기록하고 확정한다. 이미 쓴 분석 행은 남는다. */
			REJECT,

			/** 확정하지 않고 긴 대기·경보로 보류한다. */
			HOLD,
		}

		enum class SingleRecordFailurePolicy {
			/** 그 레코드를 S3 넘김 목록에 올리고 나머지를 끝낸 뒤 확정한다. */
			HANDOFF,

			/** 확정하지 않고 긴 대기·경보로 보류한다. */
			HOLD,
		}

		companion object {
			const val MAX_ARCHIVE_CONCURRENCY: Int = 64
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
		/** `s3` 클라이언트의 최대 연결 수. 비어 있으면 계산한다([Telemetry.s3MaxConnections]). */
		val s3MaxConnections: Int? = null,
	) {
		enum class Type { file, s3 }

		init {
			// 빈 버킷으로 조용히 쓰면 복구 원천이 사라진다. 기동에서 끊는다.
			require(type != Type.s3 || bucket.isNotBlank()) {
				"pulsemetry.telemetry.archive.bucket 이 비어 있다. type=s3 에는 버킷이 필요하다."
			}
			require(s3MaxConnections == null || s3MaxConnections >= 1) {
				"pulsemetry.telemetry.archive.s3-max-connections 는 1 이상이어야 한다"
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
