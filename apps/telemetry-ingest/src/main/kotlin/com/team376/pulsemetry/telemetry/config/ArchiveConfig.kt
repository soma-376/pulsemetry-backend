package com.team376.pulsemetry.telemetry.config

import com.team376.pulsemetry.telemetry.collector.archive.ArchiveWriter
import com.team376.pulsemetry.telemetry.collector.archive.FileArchiveWriter
import com.team376.pulsemetry.telemetry.collector.archive.S3ArchiveWriter
import com.team376.pulsemetry.telemetry.consumer.batch.FileHandoffListWriter
import com.team376.pulsemetry.telemetry.consumer.batch.HandoffListWriter
import com.team376.pulsemetry.telemetry.consumer.batch.S3HandoffListWriter
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.http.apache5.Apache5HttpClient
import software.amazon.awssdk.services.s3.S3Client
import java.nio.file.Path
import java.time.Clock

/**
 * 마스킹 이후 원본을 어디에 쓰는가 (ADR 0012). **배포는 S3, 로컬 dev 와 테스트는 파일이다.**
 *
 * 두 구현을 중첩 설정으로 가른 것은 S3 클라이언트의 `build()` 때문이다 — 만들 때 기본 체인에서 리전을
 * 해석하므로 리전이 없는 로컬에서 그냥 부르면 던진다(자격 증명은 첫 요청 때 찾는다). 분기를 `when` 하나로
 * 접으면 파일만 쓰는 컨텍스트도 그 코드를 지나게 된다.
 *
 * `@ConditionalOnProperty` 는 ADR 0011 Alternative A 가 **라이브러리에서** 기각한 것이지
 * 앱에서 금지한 것이 아니다. 여기서는 무엇이 켜지는지가 앱 코드에 그대로 보인다.
 */
@Configuration(proxyBeanMethods = false)
class ArchiveConfig {

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(prefix = PREFIX, name = ["type"], havingValue = "file", matchIfMissing = true)
	class File {

		@Bean
		fun archiveWriter(properties: TelemetryIngestProperties): ArchiveWriter =
			FileArchiveWriter(Path.of(properties.telemetry.archive.dir))

		/** Kafka 배치의 S3 넘김 목록(PROJ-341) — 로컬은 아카이브 디렉터리 아래 `handoff/`. */
		@Bean
		fun handoffListWriter(properties: TelemetryIngestProperties): HandoffListWriter =
			FileHandoffListWriter(Path.of(properties.telemetry.archive.dir))
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(prefix = PREFIX, name = ["type"], havingValue = "s3")
	class S3 {

		/**
		 * 원본 아카이브·넘김 목록·HTTP 경로가 함께 쓰는 클라이언트 하나(PROJ-347). 동기 HTTP 구현(Apache5)과 최대 연결 수만 정하고
		 * 자격 증명·리전은 SDK 기본 체인에 맡긴다. 연결 대기 시간 등 나머지 HTTP 설정은 SDK 기본값이다.
		 */
		@Bean(destroyMethod = "close")
		fun s3Client(properties: TelemetryIngestProperties): S3Client {
			val telemetry = properties.telemetry
			val consumer = telemetry.kafka.consumer
			val maxConnections = telemetry.s3MaxConnections()
			log.info(
				"telemetry archive s3 client: http=apache5 maxConnections={} configured={} batch={} consumers={} archiveConcurrency={}",
				maxConnections, telemetry.archive.s3MaxConnections != null, consumer.batch.enabled, consumer.concurrency, consumer.batch.archiveConcurrency,
			)
			return S3Client.builder()
				.httpClientBuilder(Apache5HttpClient.builder().maxConnections(maxConnections))
				.build()
		}

		@Bean
		fun archiveWriter(
			s3: S3Client,
			properties: TelemetryIngestProperties,
			clock: Clock,
		): ArchiveWriter = S3ArchiveWriter(
			s3 = s3,
			bucket = properties.telemetry.archive.bucket,
			basePrefix = properties.telemetry.archive.basePrefix,
			clock = clock,
		)

		/** Kafka 배치의 S3 넘김 목록(PROJ-341) — 원본과 같은 버킷·접두사의 `handoff/` 아래. 보존도 원본과 같다. */
		@Bean
		fun handoffListWriter(s3: S3Client, properties: TelemetryIngestProperties): HandoffListWriter =
			S3HandoffListWriter(s3, properties.telemetry.archive.bucket, properties.telemetry.archive.basePrefix)
	}

	companion object {
		const val PREFIX: String = "pulsemetry.telemetry.archive"

		private val log = LoggerFactory.getLogger(ArchiveConfig::class.java)
	}
}
