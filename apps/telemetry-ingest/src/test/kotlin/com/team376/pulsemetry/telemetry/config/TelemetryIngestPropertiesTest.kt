package com.team376.pulsemetry.telemetry.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
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
}
