package com.team376.pulsemetry.dashboard.cache

import org.flywaydb.core.Flyway
import javax.sql.DataSource

/**
 * RDS `dashboard_cache` 스키마를 마이그레이션한다(ADR 0023 §2·§3).
 *
 * `enrollment` 와 **다른 Flyway 인스턴스**다 — 스키마·이력 테이블·SQL 위치가 모두 따로다.
 * `Flyway` 타입 빈으로 노출하지 않는다 — Boot 의 Flyway 자동설정은 그 타입의 빈이 있으면 물러난다.
 */
class RdsCacheSchema(
	private val dataSource: DataSource,
) {

	/** 아직 적용되지 않은 마이그레이션을 실행하고 실행한 개수를 돌려준다. 이미 최신이면 0 이다. */
	fun migrate(): Int =
		Flyway.configure()
			.dataSource(dataSource)
			.schemas(SCHEMA)
			.defaultSchema(SCHEMA)
			.table(HISTORY_TABLE)
			.locations(LOCATION)
			// 위치를 못 찾으면 아무것도 적용하지 않고 성공하는 대신 실패한다 — 리소스가 빠진 배포를 드러낸다.
			.failOnMissingLocations(true)
			.load()
			.migrate()
			.migrationsExecuted

	companion object {
		const val SCHEMA: String = "dashboard_cache"
		const val HISTORY_TABLE: String = "flyway_schema_history"
		const val LOCATION: String = "classpath:db/dashboard-cache"
	}
}
