package com.team376.pulsemetry.persistence.telemetryops

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

/**
 * 보존 작업 기록(ADR 0024 §4·§5)의 수명을 실제 PostgreSQL 위에서 본다 — `running` 에서 끝 상태로 한 번만 가고, 논리 삭제 완료는
 * 발효된 경계와 두 테이블의 삭제 수 없이는 기록되지 않는다.
 */
@Testcontainers
class RetentionOperationStoreTest {

	private val dataSource = PGSimpleDataSource().apply {
		setURL(postgres.jdbcUrl)
		user = postgres.username
		password = postgres.password
	}

	private val store = RetentionOperationStore(dataSource)
	private val tenant: UUID = UUID.randomUUID()

	private val asOf = Instant.parse("2026-09-24T01:00:00Z")
	private val requested = Instant.parse("2025-09-23T15:00:00Z")
	private val later = Instant.parse("2025-10-23T15:00:00Z")
	private val counts = RetentionDeletionCounts(eventObservations = 1, eventRows = 2, metricPointObservations = 3, metricPointRows = 3)

	@BeforeEach
	fun setUp() {
		TelemetryOpsSchemaMigrator(dataSource).migrate()
	}

	private fun started(): UUID = store.start(tenant, 12, asOf, requested, asOf)

	@Test
	@DisplayName("열면 running 이고 경계는 발효 전이다")
	fun startIsRunningWithoutABoundary() {
		val id = started()

		val operation = store.find(id)!!
		assertThat(operation.status).isEqualTo(RetentionOperationStatus.RUNNING)
		assertThat(operation.requestedBefore).isEqualTo(requested)
		assertThat(operation.deletedBefore).isNull()
		assertThat(operation.policyEpoch).isNull()
		assertThat(operation.finishedAt).isNull()
		assertThat(operation.counts).isNull()
	}

	@Test
	@DisplayName("논리 삭제 완료 — 발효된 경계(요청보다 늦을 수 있다)·epoch·두 테이블의 관측 수와 행 수를 따로 남긴다")
	fun logicallyDeletedKeepsBoundaryAndCounts() {
		val id = started()
		store.boundaryApplied(id, later, policyEpoch = 4)
		store.logicallyDeleted(id, counts, asOf.plusSeconds(5))

		val operation = store.find(id)!!
		assertThat(operation.status).isEqualTo(RetentionOperationStatus.LOGICALLY_DELETED)
		assertThat(operation.deletedBefore).isEqualTo(later)
		assertThat(operation.policyEpoch).isEqualTo(4)
		assertThat(operation.counts).isEqualTo(counts)
		assertThat(operation.finishedAt).isEqualTo(asOf.plusSeconds(5))
	}

	@Test
	@DisplayName("경계 발효 없이 논리 삭제 완료를 기록할 수 없다")
	fun logicallyDeletedNeedsAnAppliedBoundary() {
		val id = started()

		assertThatThrownBy { store.logicallyDeleted(id, counts, asOf) }.isInstanceOf(SQLException::class.java)
		assertThat(store.find(id)!!.status).isEqualTo(RetentionOperationStatus.RUNNING)
	}

	@Test
	@DisplayName("미완료는 사유를 남기고, 센 수가 없으면 수도 비어 있다")
	fun incompleteKeepsTheReason() {
		val id = started()
		store.boundaryApplied(id, requested, policyEpoch = 1)
		store.incomplete(id, "drain timeout", counts = null, finishedAt = asOf.plusSeconds(1))

		val operation = store.find(id)!!
		assertThat(operation.status).isEqualTo(RetentionOperationStatus.INCOMPLETE)
		assertThat(operation.detail).isEqualTo("drain timeout")
		assertThat(operation.counts).isNull()
	}

	@Test
	@DisplayName("끝난 기록은 다시 바꾸지 않는다 — 끝 상태에서의 갱신은 실패한다")
	fun finishedOperationsAreFinal() {
		val id = started()
		store.failed(id, "clickhouse unreachable", asOf)

		assertThatThrownBy { store.boundaryApplied(id, requested, 1) }.isInstanceOf(IllegalStateException::class.java)
		assertThatThrownBy { store.failed(id, "again", asOf) }.isInstanceOf(IllegalStateException::class.java)
		assertThat(store.find(id)!!.status).isEqualTo(RetentionOperationStatus.FAILED)
		assertThat(store.find(id)!!.detail).isEqualTo("clickhouse unreachable")
	}

	@Test
	@DisplayName("발효된 경계는 요청 경계보다 이를 수 없다 — 경계는 MAX 다")
	fun appliedBoundaryIsNeverEarlierThanRequested() {
		val id = started()

		assertThatThrownBy { store.boundaryApplied(id, requested.minusSeconds(1), 1) }.isInstanceOf(SQLException::class.java)
	}

	companion object {
		@Container
		@JvmStatic
		val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:16-alpine")
	}
}
