package com.team376.pulsemetry.telemetry.config

import com.team376.pulsemetry.telemetry.collector.archive.ArchiveWriter
import com.team376.pulsemetry.telemetry.collector.archive.FileArchiveWriter
import com.team376.pulsemetry.telemetry.collector.archive.S3ArchiveWriter
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.services.s3.S3Client
import java.nio.file.Path
import java.time.Clock

/**
 * 마스킹 이후 원본을 어디에 쓰는가 (ADR 0012). **배포는 S3, 로컬 dev 와 테스트는 파일이다.**
 *
 * 두 구현을 중첩 설정으로 가른 것은 `S3Client.create()` 때문이다 — 자격 증명과 리전을 즉시
 * 해석하므로 로컬에서 그냥 부르면 던진다. 분기를 `when` 하나로 접으면 파일만 쓰는 컨텍스트도
 * 그 코드를 지나게 된다.
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
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(prefix = PREFIX, name = ["type"], havingValue = "s3")
	class S3 {

		@Bean(destroyMethod = "close")
		fun s3Client(): S3Client = S3Client.create()

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
	}

	companion object {
		const val PREFIX: String = "pulsemetry.telemetry.archive"
	}
}
