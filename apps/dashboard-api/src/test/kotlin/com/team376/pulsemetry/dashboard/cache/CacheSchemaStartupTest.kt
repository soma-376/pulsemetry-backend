package com.team376.pulsemetry.dashboard.cache

import com.team376.pulsemetry.dashboard.support.AbstractDashboardApiTest
import com.team376.pulsemetry.dashboard.support.DashboardTestStores
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 앱 기동이 두 캐시 스키마를 캐시 계정으로 적용한다 (ADR 0023 §3). */
class CacheSchemaStartupTest : AbstractDashboardApiTest() {

	@Test
	@DisplayName("기동 뒤 RDS dashboard_cache 와 ClickHouse dashboard_cache 의 테이블이 있다")
	fun startupAppliesBothSchemas() {
		val rdsTables = DashboardTestStores.writer.sql(
			"SELECT table_name FROM information_schema.tables WHERE table_schema = 'dashboard_cache' ORDER BY 1",
		).query(String::class.java).list()
		assertThat(rdsTables).contains("snapshots", "snapshot_teams", "snapshot_members", "flyway_schema_history")

		val clickHouseTables = DashboardTestStores.clickHouseAdmin(
			"SELECT name FROM system.tables WHERE database = '${DashboardTestStores.CACHE_DATABASE}' ORDER BY name FORMAT TSVRaw",
		).lines().filter { it.isNotEmpty() }
		assertThat(clickHouseTables).contains("snapshot_intake", "snapshot_usage", "snapshot_observed_days", "snapshot_member_activity")
	}
}
