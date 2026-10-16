package com.team376.pulsemetry.telemetry.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 기동을 막아야 하는 값이 실제로 막는지 확인한다. Spring 없이 돈다. */
class TelemetryIngestPropertiesTest {

	@Test
	@DisplayName("현재 공시 카탈로그 기본 배선과 none은 생성되며 알 수 없는 가격 이름은 기동에서 거절한다")
	fun pricingProfileWiring() {
		val properties = TelemetryIngestProperties("test-secret")
		assertThat(properties.telemetry.ingest.pricingProfile).isEqualTo("public-standard-global-reference-2026-10-16-v4")
		assertThat(PipelineConfig().observationPipeline(properties)).isNotNull()
		val v1 = properties.copy(telemetry = properties.telemetry.copy(ingest = properties.telemetry.ingest.copy(pricingProfile = "public-standard-global-reference-2026-10-16-v1")))
		assertThat(PipelineConfig().observationPipeline(v1)).isNotNull()
		val v2 = properties.copy(telemetry = properties.telemetry.copy(ingest = properties.telemetry.ingest.copy(pricingProfile = "public-standard-global-reference-2026-10-16-v2")))
		assertThat(PipelineConfig().observationPipeline(v2)).isNotNull()
		val v3 = properties.copy(telemetry = properties.telemetry.copy(ingest = properties.telemetry.ingest.copy(pricingProfile = "public-standard-global-reference-2026-10-16-v3")))
		assertThat(PipelineConfig().observationPipeline(v3)).isNotNull()
		val v4 = properties.copy(telemetry = properties.telemetry.copy(ingest = properties.telemetry.ingest.copy(pricingProfile = "public-standard-global-reference-2026-10-16-v4")))
		assertThat(PipelineConfig().observationPipeline(v4)).isNotNull()
		val none = properties.copy(telemetry = properties.telemetry.copy(ingest = properties.telemetry.ingest.copy(pricingProfile = "none")))
		assertThat(PipelineConfig().observationPipeline(none)).isNotNull()
		val invalid = properties.copy(telemetry = properties.telemetry.copy(ingest = properties.telemetry.ingest.copy(pricingProfile = "unknown")))
		assertThatThrownBy { PipelineConfig().observationPipeline(invalid) }.isInstanceOf(IllegalArgumentException::class.java)
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
	@DisplayName("ClickHouse URL 이 비면 기동하지 못한다")
	fun aBlankClickHouseUrlIsRejected() {
		assertThatThrownBy { TelemetryIngestProperties.ClickHouse(url = " ") }
			.isInstanceOf(IllegalArgumentException::class.java)
			.hasMessageContaining("clickhouse.url")
	}
}
