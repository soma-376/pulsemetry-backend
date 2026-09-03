package com.team376.pulsemetry.telemetry.enricher.support

import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * 실제 PostgreSQL 위에서 도는 보강 통합 테스트의 공통 기반.
 * 컨텍스트가 뜨는 시점에 Flyway 가 마이그레이션을 적용한다.
 *
 * as-of 조인은 `teams.status` native enum(ADR 0009)과 스키마 조인에 걸려 있어 임베디드 DB 로
 * 대체할 수 없다.
 */
@SpringBootTest(classes = [EnricherTestApplication::class])
@Import(PostgresContainerConfig::class)
abstract class AbstractEnricherIntegrationTest
