package com.team376.pulsemetry.persistence.telemetryops

import org.flywaydb.core.Flyway
import javax.sql.DataSource

/**
 * RDS `telemetry_ops` 스키마를 마이그레이션한다 (ADR 0021).
 *
 * `enrollment` 와 **다른 Flyway 인스턴스**다 — 스키마·이력 테이블·SQL 위치가 모두 따로다. 그래서
 * `enrollment` 와 버전 번호도 이력도 섞이지 않는다.
 * V3의 조직 생성 트리거 때문에 enrollment 마이그레이션 완료 후 호출한다(ADR 0034).
 *
 * ## 조립
 *
 * 빈이 아니다(ADR 0011). **어느 프로세스가 언제 부를지는 앱이 정한다** — 지금은 `:apps:enrollment-api`
 * 가 기동 중에 한 번 부른다. `:apps:telemetry-ingest` 는 이 클래스를 조립하지 않는다 — ingest 기동이
 * DDL 을 실행하지 않는 것이 이 스키마를 분리한 취지다.
 *
 * 앱이 이 클래스를 감쌀 때 **`Flyway` 타입 빈으로 노출하지 마라.** Boot 의 Flyway 자동설정은 그 타입의
 * 빈이 이미 있으면 물러나므로 `enrollment` 마이그레이션이 조용히 멈춘다.
 */
public class TelemetryOpsSchemaMigrator(
	private val dataSource: DataSource,
) {

	/** 아직 적용되지 않은 마이그레이션을 실행하고 실행한 개수를 돌려준다. 이미 최신이면 0 이다. */
	public fun migrate(): Int =
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

	public companion object {
		public const val SCHEMA: String = "telemetry_ops"
		public const val HISTORY_TABLE: String = "flyway_schema_history"
		public const val LOCATION: String = "classpath:db/telemetry-ops"
	}
}
