package com.team376.pulsemetry.security.support

import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * 실제 PostgreSQL 위에서 도는 인증 통합 테스트의 공통 기반.
 * 컨텍스트가 뜨는 시점에 Flyway 가 마이그레이션을 적용한다.
 */
@SpringBootTest(classes = [SecurityTestApplication::class])
@Import(PostgresContainerConfig::class)
abstract class AbstractSecurityIntegrationTest
