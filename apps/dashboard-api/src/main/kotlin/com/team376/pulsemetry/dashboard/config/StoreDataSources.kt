package com.team376.pulsemetry.dashboard.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory
import io.micrometer.core.instrument.MeterRegistry

internal object StoreDataSources {

	/**
	 * [readOnly] 면 커넥션을 읽기 전용으로 연다. PostgreSQL 드라이버의 `readOnlyMode` 기본값(`transaction`)은 자동 커밋 문장에
	 * 읽기 전용을 걸지 않으므로 `always` 로 둔다.
	 *
	 * [registry] 가 있으면 풀을 열기 전에 Hikari 지표(`hikaricp_*`)를 붙인다(ADR 0079). 이 생성자는 풀을 바로 열어 설정을 봉인하므로
	 * Boot 의 Hikari binder 가 나중에 붙이지 못한다(경고만 남긴다).
	 */
	fun hikari(poolName: String, connection: DashboardApiProperties.RdsConnection, readOnly: Boolean, registry: MeterRegistry? = null): HikariDataSource =
		HikariDataSource(
			HikariConfig().apply {
				this.poolName = poolName
				jdbcUrl = connection.url
				username = connection.username
				password = connection.password
				connectionTimeout = connection.connectionTimeout.toMillis()
				registry?.let { metricsTrackerFactory = MicrometerMetricsTrackerFactory(it) }
				if (readOnly) {
					isReadOnly = true
					addDataSourceProperty("readOnlyMode", "always")
				}
			},
		)
}
