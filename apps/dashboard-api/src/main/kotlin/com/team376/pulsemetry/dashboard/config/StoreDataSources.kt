package com.team376.pulsemetry.dashboard.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

internal object StoreDataSources {

	/**
	 * [readOnly] 면 커넥션을 읽기 전용으로 연다. PostgreSQL 드라이버의 `readOnlyMode` 기본값(`transaction`)은 자동 커밋 문장에
	 * 읽기 전용을 걸지 않으므로 `always` 로 둔다.
	 */
	fun hikari(poolName: String, connection: DashboardApiProperties.RdsConnection, readOnly: Boolean): HikariDataSource =
		HikariDataSource(
			HikariConfig().apply {
				this.poolName = poolName
				jdbcUrl = connection.url
				username = connection.username
				password = connection.password
				connectionTimeout = connection.connectionTimeout.toMillis()
				if (readOnly) {
					isReadOnly = true
					addDataSourceProperty("readOnlyMode", "always")
				}
			},
		)
}
