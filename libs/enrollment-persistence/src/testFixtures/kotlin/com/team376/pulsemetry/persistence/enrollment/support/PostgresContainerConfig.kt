package com.team376.pulsemetry.persistence.enrollment.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * 통합 테스트용 PostgreSQL 컨테이너.
 *
 * 컨테이너를 빈으로 등록하면 Spring 이 수명주기를 관리하고 컨텍스트 캐시에 얹히므로
 * 테스트 클래스마다 새로 뜨지 않는다. [ServiceConnection] 이 DataSource 속성을 채운다.
 *
 * H2 등 임베디드 DB 로 대체하지 않는다 — jsonb·부분 유니크 인덱스·스키마는 PostgreSQL 전용이다.
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
