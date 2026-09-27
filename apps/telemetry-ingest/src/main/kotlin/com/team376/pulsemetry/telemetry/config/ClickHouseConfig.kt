package com.team376.pulsemetry.telemetry.config

import com.team376.pulsemetry.persistence.telemetry.ClickHouseHttpClient
import com.team376.pulsemetry.persistence.telemetry.ClickHouseSchemaMigrator
import com.team376.pulsemetry.persistence.telemetry.TelemetryEventsSink
import com.team376.pulsemetry.persistence.telemetry.TelemetryMetricPointsSink
import com.team376.pulsemetry.telemetry.pipeline.ClickHouseSchema
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** ClickHouse 접속·적재·스키마 적용. 접속 정보는 설정에서 오고 라이브러리는 값만 받는다(ADR 0011). */
@Configuration(proxyBeanMethods = false)
class ClickHouseConfig {

	@Bean
	fun clickHouseHttpClient(properties: TelemetryIngestProperties): ClickHouseHttpClient {
		val clickhouse = properties.telemetry.clickhouse
		return ClickHouseHttpClient(
			baseUrl = clickhouse.url,
			database = clickhouse.database,
			timeout = clickhouse.timeout,
		)
	}

	@Bean
	fun clickHouseSchemaMigrator(client: ClickHouseHttpClient): ClickHouseSchemaMigrator =
		ClickHouseSchemaMigrator(client)

	@Bean
	fun clickHouseSchema(
		migrator: ClickHouseSchemaMigrator,
		properties: TelemetryIngestProperties,
	): ClickHouseSchema {
		val schema = properties.telemetry.clickhouse.schema
		return ClickHouseSchema(migrator, schema.startupAttempts, schema.startupBackoff)
	}

	/** 분석 테이블 둘(ADR 0020). 구 `enriched_events` 에는 쓰지 않는다 — 이중 적재하지 않는다. */
	@Bean
	fun telemetryEventsSink(client: ClickHouseHttpClient): TelemetryEventsSink = TelemetryEventsSink(client)

	@Bean
	fun telemetryMetricPointsSink(client: ClickHouseHttpClient): TelemetryMetricPointsSink = TelemetryMetricPointsSink(client)
}
