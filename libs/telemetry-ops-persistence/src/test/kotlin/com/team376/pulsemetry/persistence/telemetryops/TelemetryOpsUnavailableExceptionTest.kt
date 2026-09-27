package com.team376.pulsemetry.persistence.telemetryops

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import java.time.Instant
import java.util.UUID

/**
 * 요약 쓰기의 실패 분류. 일시 장애만 [TelemetryOpsUnavailableException](503)이고 **넓히지 않는다**. 나머지는 분류하지 않고
 * 전파한다 — 앱 표의 "그 밖의 예외"(503)다. 영구(400)로 올리는 분류는 없다.
 */
@Testcontainers
class TelemetryOpsUnavailableExceptionTest {

	@Test
	@DisplayName("연결·자원·운영자 종료·timeout·직렬화·교착·잠금·스키마 미적용은 일시 장애다")
	fun transientStates() {
		for (state in listOf("08006", "08001", "53300", "57P01", "57014", "40001", "40P01", "55P03", "3F000", "42P01")) {
			assertThat(TelemetryOpsUnavailableException.isTransient(SQLException("x", state))).describedAs(state).isTrue()
		}
		assertThat(TelemetryOpsUnavailableException.isTransient(SQLTransientConnectionException("x"))).isTrue()
	}

	@Test
	@DisplayName("제약 위반·문법 오류·상태 없는 예외는 일시 장애가 아니다 — 분류하지 않고 전파한다")
	fun otherStatesAreNotTransient() {
		for (state in listOf("23505", "23514", "42601", "22007")) {
			assertThat(TelemetryOpsUnavailableException.isTransient(SQLException("x", state))).describedAs(state).isFalse()
		}
		assertThat(TelemetryOpsUnavailableException.isTransient(SQLException("x"))).isFalse()
	}

	@Test
	@DisplayName("스키마가 아직 적용되지 않은 DB 에 쓰면 일시 장애다 — DDL 은 enrollment-api 기동이 적용한다")
	fun missingSchemaIsUnavailable() {
		val bare = PGSimpleDataSource().apply {
			setURL(postgres.jdbcUrl)
			user = postgres.username
			password = postgres.password
		}

		assertThatThrownBy { TenantIngestSummaryStore(bare).record(UUID.randomUUID(), SummaryOrigin.LIVE, Instant.EPOCH, null) }
			.isInstanceOf(TelemetryOpsUnavailableException::class.java)
			.hasMessageContaining("42P01")
	}

	companion object {
		/** 마이그레이션을 적용하지 않은 DB. */
		@Container
		@JvmStatic
		val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:16-alpine")
	}
}
