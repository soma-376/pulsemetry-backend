package com.team376.pulsemetry.enrollment.config

import com.team376.pulsemetry.persistence.telemetryops.TelemetryOpsSchemaMigrator
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

/**
 * RDS `telemetry_ops` 스키마를 이 앱의 기동에서 마이그레이션한다 (ADR 0021).
 *
 * 이 앱이 이미 RDS 마이그레이션을 실행하는 유일한 프로세스라 새 실행 주체를 배포에 더하지 않는다.
 * `enrollment` 마이그레이션(Boot 의 Flyway 자동설정)과 **다른 Flyway 인스턴스**다 — 스키마·이력·SQL 위치가
 * 따로다. `:apps:telemetry-ingest` 는 이 설정을 갖지 않는다 — ingest 기동은 DDL 을 실행하지 않는다.
 *
 * ⚠️ 마이그레이터를 `Flyway` 타입 빈으로 노출하지 마라. Boot 의 Flyway 자동설정은 그 타입의 빈이 있으면
 * 물러나 `enrollment` 마이그레이션이 조용히 멈춘다. 그래서 평범한 클래스를 빈으로 두고 초기화 콜백에서 부른다.
 * 실패하면 `enrollment` 마이그레이션과 같이 기동이 실패한다.
 */
@Configuration
class TelemetryOpsMigrationConfig {

	@Bean
	fun telemetryOpsSchemaMigrator(dataSource: DataSource): TelemetryOpsSchemaMigrator =
		TelemetryOpsSchemaMigrator(dataSource)

	@Bean
	@DependsOnDatabaseInitialization
	fun telemetryOpsSchemaMigration(migrator: TelemetryOpsSchemaMigrator): InitializingBean =
		InitializingBean { migrator.migrate() }
}
