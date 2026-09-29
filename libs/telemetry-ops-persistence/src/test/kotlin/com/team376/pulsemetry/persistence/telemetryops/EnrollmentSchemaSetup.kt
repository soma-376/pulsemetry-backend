package com.team376.pulsemetry.persistence.telemetryops

import org.flywaydb.core.Flyway
import javax.sql.DataSource

/** 운영 조립과 같은 순서로 원래 소유 모듈의 스키마를 준비한다. */
internal fun prepareEnrollmentSchema(dataSource: DataSource) {
    Flyway.configure().dataSource(dataSource).schemas("enrollment").defaultSchema("enrollment")
        .locations("classpath:db/migration").load().migrate()
}
