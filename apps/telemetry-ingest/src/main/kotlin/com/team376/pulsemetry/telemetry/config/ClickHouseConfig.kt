package com.team376.pulsemetry.telemetry.config

import com.team376.pulsemetry.persistence.telemetry.ClickHouseHttpClient
import com.team376.pulsemetry.persistence.telemetry.ClickHouseSchemaMigrator
import com.team376.pulsemetry.persistence.telemetry.EnrichedEventsSink
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

	@Bean
	fun enrichedEventsSink(client: ClickHouseHttpClient): EnrichedEventsSink =
		EnrichedEventsSink(client)
}
