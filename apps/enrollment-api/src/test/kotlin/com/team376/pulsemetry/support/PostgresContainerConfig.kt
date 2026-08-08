package com.team376.pulsemetry.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * 앱 통합 테스트용 PostgreSQL 컨테이너.
 *
 * 컨텍스트가 뜰 때 Flyway 가 enrollment 스키마를 적용하므로 실제 DB 없이는 기동 자체가 안 된다.
 * 컨테이너를 빈으로 등록해 Spring 이 수명주기를 관리하게 하면 컨텍스트 캐시를 타고 재사용된다.
 */
@TestConfiguration(proxyBeanMethods = false)
class PostgresContainerConfig {

	@Bean
	@ServiceConnection
	fun postgresContainer(): PostgreSQLContainer =
		PostgreSQLContainer(POSTGRES_IMAGE)

	companion object {
		/** docker-compose.yml 과 같은 메이저 버전을 쓴다. */
		const val POSTGRES_IMAGE = "postgres:17-alpine"
	}
}
