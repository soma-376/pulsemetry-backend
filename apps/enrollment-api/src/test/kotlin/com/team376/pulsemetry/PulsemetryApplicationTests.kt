package com.team376.pulsemetry

import com.team376.pulsemetry.support.PostgresContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient

@SpringBootTest
@Import(PostgresContainerConfig::class)
class PulsemetryApplicationTests {

	@Autowired
	private lateinit var jdbcClient: JdbcClient

	@Test
	fun contextLoads() {
	}

	@Test
	@DisplayName("애플리케이션 기동 시 Flyway 가 enrollment 스키마를 적용한다")
	fun flywayMigratesOnStartup() {
		val tables = jdbcClient
			.sql(
				"""
				SELECT count(*) FROM information_schema.tables
				WHERE table_schema = 'enrollment' AND table_type = 'BASE TABLE'
				""",
			)
			.query(Long::class.javaObjectType)
			.single()

		// 도메인 테이블 14종 + flyway_schema_history
		assertThat(tables).isEqualTo(15L)
	}
}
