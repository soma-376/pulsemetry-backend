package com.team376.pulsemetry

import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
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
				SELECT table_name FROM information_schema.tables
				WHERE table_schema = 'enrollment' AND table_type = 'BASE TABLE'
				""",
			)
			.query(String::class.java)
			.list()

		// 개수를 세지 않는다 — 테이블이 하나 늘 때마다 이 테스트가 깨지면 신호가 아니라 소음이다.
		// enrollment 흐름이 기대는 테이블이 **있는지**만 본다.
		assertThat(tables).containsAll(REQUIRED_TABLES)
	}

	private companion object {
		val REQUIRED_TABLES = listOf(
			"tenants",
			"members",
			"invitations",
			"installations",
			"installation_credentials",
			"telemetry_tokens",
			"manifests",
			"installation_manifest_assignments",
		)
	}
}
