package com.team376.pulsemetry.telemetry.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 기동을 막아야 하는 값이 실제로 막는지 확인한다. Spring 없이 돈다. */
class TelemetryIngestPropertiesTest {

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
