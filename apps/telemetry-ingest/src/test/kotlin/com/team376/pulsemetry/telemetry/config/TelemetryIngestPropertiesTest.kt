package com.team376.pulsemetry.telemetry.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.FileSystemResource
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** 기동을 막아야 하는 값이 실제로 막는지 확인한다. Spring 없이 돈다. */
class TelemetryIngestPropertiesTest {

	@Test
	@DisplayName("가격판 설정 없이 단일 참조 카탈로그를 배선한다")
	fun singleCatalogWiring() {
		assertThat(PipelineConfig().observationPipeline(TelemetryIngestProperties("test-secret"))).isNotNull()
	}

	@Test
	@DisplayName("type=s3 인데 버킷이 비면 기동하지 못한다 — 빈 버킷은 복구 원천을 지운다")
	fun s3WithoutABucketIsRejected() {
		assertThatThrownBy {
			TelemetryIngestProperties.Archive(type = TelemetryIngestProperties.Archive.Type.s3)
		}.isInstanceOf(IllegalArgumentException::class.java)
			.hasMessageContaining("bucket")
	}

	@Test
	@DisplayName("type=file 은 버킷이 없어도 된다 — 로컬 dev 의 기본값이다")
	fun fileNeedsNoBucket() {
		val archive = TelemetryIngestProperties.Archive()

		assertThat(archive.type).isEqualTo(TelemetryIngestProperties.Archive.Type.file)
	}

	@Test
	@DisplayName("배치 모드에서 마감 직전에 시작한 ClickHouse 요청이 max-poll-interval 을 넘을 수 있으면 기동하지 못한다")
	fun batchClickHouseTimeoutMustFitPollInterval() {
		fun telemetry(timeoutSeconds: Long) = TelemetryIngestProperties.Telemetry(
			clickhouse = TelemetryIngestProperties.ClickHouse(timeout = Duration.ofSeconds(timeoutSeconds)),
			kafka = TelemetryIngestProperties.Kafka(
				consumer = TelemetryIngestProperties.Consumer(batch = TelemetryIngestProperties.Batch(enabled = true)),
			),
		)

		// 기본값: 420 - 35 + 30 = 415초 < 600초.
		assertThat(telemetry(30).kafka.consumer.batch.enabled).isTrue()
		// 420 - 35 + 240 = 625초 ≥ 600초.
		assertThatThrownBy { telemetry(240) }
			.isInstanceOf(IllegalArgumentException::class.java)
			.hasMessageContaining("clickhouse.timeout")
	}

	@Test
	@DisplayName("ClickHouse URL 이 비면 기동하지 못한다")
	fun aBlankClickHouseUrlIsRejected() {
		assertThatThrownBy { TelemetryIngestProperties.ClickHouse(url = " ") }
			.isInstanceOf(IllegalArgumentException::class.java)
			.hasMessageContaining("clickhouse.url")
	}

	@ParameterizedTest(name = "소비자 {0} × (동시 {1} + 1) → {2}")
	@CsvSource(
		"3, 16, 51",
		"6, 16, 102",
		"6, 8, 54",
		// 기본값(소비자 1, 동시 8)은 9 라서 SDK 기본값 50 이 하한으로 남는다.
		"1, 8, 50",
	)
	@DisplayName("배치 모드의 S3 최대 연결 수는 max(50, 소비자 수 × (동시 수 + 1)) 이다")
	fun batchS3MaxConnections(consumers: Int, archiveConcurrency: Int, expected: Int) {
		assertThat(batchTelemetry(consumers, archiveConcurrency).s3MaxConnections()).isEqualTo(expected)
	}

	@Test
	@DisplayName("배치가 꺼져 있으면 S3 최대 연결 수는 SDK 기본값 50 이다 — 소비자 수·동시 수를 보지 않는다")
	fun s3MaxConnectionsWithoutBatch() {
		val telemetry = TelemetryIngestProperties.Telemetry(
			kafka = TelemetryIngestProperties.Kafka(
				consumer = TelemetryIngestProperties.Consumer(concurrency = 6, batch = TelemetryIngestProperties.Batch(archiveConcurrency = 16)),
			),
		)

		assertThat(TelemetryIngestProperties.Telemetry().s3MaxConnections()).isEqualTo(50)
		assertThat(telemetry.s3MaxConnections()).isEqualTo(50)
	}

	@Test
	@DisplayName("명시한 S3 최대 연결 수가 계산값보다 우선한다 — 배치가 꺼져 있으면 50 보다 작아도 된다")
	fun configuredS3MaxConnectionsWins() {
		assertThat(batchTelemetry(6, 8, configured = 80).s3MaxConnections()).isEqualTo(80)
		// 배치 연결 수 54 와 같으면 통과한다.
		assertThat(batchTelemetry(6, 8, configured = 54).s3MaxConnections()).isEqualTo(54)
		assertThat(
			TelemetryIngestProperties.Telemetry(archive = s3Archive(configured = 10)).s3MaxConnections(),
		).isEqualTo(10)
	}

	@Test
	@DisplayName("배치 모드에서 명시한 S3 최대 연결 수가 소비자 수 × (동시 수 + 1) 보다 작으면 기동하지 못한다")
	fun insufficientConfiguredS3MaxConnectionsIsRejected() {
		assertThatThrownBy { batchTelemetry(6, 16, configured = 101) }
			.isInstanceOf(IllegalArgumentException::class.java)
			.hasMessageContaining("s3-max-connections")
			.hasMessageContaining("102")
		assertThat(batchTelemetry(6, 16, configured = 102).s3MaxConnections()).isEqualTo(102)
	}

	@ParameterizedTest(name = "s3-max-connections={0}")
	@ValueSource(ints = [0, -1])
	@DisplayName("명시한 S3 최대 연결 수는 1 이상이어야 한다")
	fun nonPositiveS3MaxConnectionsIsRejected(configured: Int) {
		assertThatThrownBy { s3Archive(configured) }
			.isInstanceOf(IllegalArgumentException::class.java)
			.hasMessageContaining("s3-max-connections")
	}

	@ParameterizedTest(name = "archive-concurrency={0}")
	@ValueSource(ints = [0, 65])
	@DisplayName("배치 아카이브 동시 수는 1 이상 64 이하다 — 상한은 오타를 막는다")
	fun archiveConcurrencyBounds(archiveConcurrency: Int) {
		assertThatThrownBy { TelemetryIngestProperties.Batch(archiveConcurrency = archiveConcurrency) }
			.isInstanceOf(IllegalArgumentException::class.java)
			.hasMessageContaining("archive-concurrency")
		assertThat(TelemetryIngestProperties.Batch(archiveConcurrency = 64).archiveConcurrency).isEqualTo(64)
	}

	@Test
	@DisplayName("application.yaml 의 S3 최대 연결 수 환경변수가 비어 있으면 null 로 바인딩돼 계산값을 쓰고, 값이 있으면 그 값이다")
	fun s3MaxConnectionsBindsFromApplicationYaml() {
		val unset = bindApplicationYaml()
		assertThat(unset.telemetry.archive.s3MaxConnections).isNull()
		assertThat(unset.telemetry.s3MaxConnections()).isEqualTo(50)

		val configured = bindApplicationYaml("PULSEMETRY_ARCHIVE_S3_MAX_CONNECTIONS" to "80")
		assertThat(configured.telemetry.archive.s3MaxConnections).isEqualTo(80)
		assertThat(configured.telemetry.s3MaxConnections()).isEqualTo(80)
	}

	private fun s3Archive(configured: Int?) = TelemetryIngestProperties.Archive(
		type = TelemetryIngestProperties.Archive.Type.s3,
		bucket = "pulsemetry-test-archive",
		s3MaxConnections = configured,
	)

	private fun batchTelemetry(consumers: Int, archiveConcurrency: Int, configured: Int? = null) = TelemetryIngestProperties.Telemetry(
		archive = s3Archive(configured),
		kafka = TelemetryIngestProperties.Kafka(
			consumer = TelemetryIngestProperties.Consumer(
				concurrency = consumers,
				batch = TelemetryIngestProperties.Batch(enabled = true, archiveConcurrency = archiveConcurrency),
			),
		),
	)

	/**
	 * 배포와 같은 main `application.yaml` 을 바인딩한다. 테스트 클래스패스의 같은 이름 파일이 main 을 가리므로 파일로 읽는다.
	 * [env] 는 환경변수 자리다. 테스트 JVM 의 시스템 속성·실제 환경변수는 떼어 낸다.
	 */
	private fun bindApplicationYaml(vararg env: Pair<String, Any>): TelemetryIngestProperties {
		val yaml = Path.of("src/main/resources/application.yaml")
		assertThat(Files.exists(yaml)).isTrue()
		val environment = StandardEnvironment().apply {
			propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
			propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
			YamlPropertySourceLoader().load("main-application-yaml", FileSystemResource(yaml)).forEach(propertySources::addLast)
			propertySources.addFirst(MapPropertySource("env", env.toMap()))
		}
		return Binder.get(environment).bind("pulsemetry", Bindable.of(TelemetryIngestProperties::class.java)).get()
	}
}
