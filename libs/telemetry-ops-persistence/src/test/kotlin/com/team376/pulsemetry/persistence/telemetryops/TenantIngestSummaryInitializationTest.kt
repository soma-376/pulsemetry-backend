package com.team376.pulsemetry.persistence.telemetryops

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

/** 조직 생성과 요약 초기화의 원자성 및 기존 이력 보존(ADR 0034). */
@Testcontainers
class TenantIngestSummaryInitializationTest {

	private val dataSource = PGSimpleDataSource().apply {
		setURL(postgres.jdbcUrl)
		user = postgres.username
		password = postgres.password
	}
	private val store = TenantIngestSummaryStore(dataSource)
	private val tenant = UUID.randomUUID()

	@BeforeEach
	fun setUp() {
		prepareEnrollmentSchema(dataSource)
		TelemetryOpsSchemaMigrator(dataSource).migrate()
	}

	@Test
	fun newTenantStartsEmptyAndFirstReceiptUpdatesTheSummary() {
		execute(insertTenant())
		assertThat(store.find(tenant)).isEqualTo(TenantIngestSummary(tenant, null, null, null, false))
		val received = Instant.parse("2026-09-29T00:00:00Z")
		val observed = received.minusSeconds(60)
		store.record(tenant, SummaryOrigin.LIVE, received, observed)
		assertThat(store.find(tenant)).isEqualTo(TenantIngestSummary(tenant, received, observed, received, false))
	}

	@Test
	fun rollbackRemovesBothTenantAndSummary() {
		dataSource.connection.use { connection ->
			connection.autoCommit = false
			connection.createStatement().use { statement ->
				statement.execute(insertTenant())
				statement.executeQuery("SELECT count(*) FROM telemetry_ops.tenant_ingest_summary WHERE tenant_id='$tenant'").use {
					it.next()
					assertThat(it.getInt(1)).isEqualTo(1)
				}
			}
			connection.rollback()
		}
		assertThat(countTenant()).isZero()
		assertThat(store.find(tenant)).isNull()
	}

	@Test
	fun summaryFailurePreventsTenantCreation() {
		execute("ALTER TABLE telemetry_ops.tenant_ingest_summary ADD CONSTRAINT reject_test_tenant CHECK (tenant_id <> '$tenant')")
		try {
			assertThatThrownBy { execute(insertTenant()) }.isInstanceOf(SQLException::class.java)
				.hasMessageContaining("reject_test_tenant")
			assertThat(countTenant()).isZero()
			assertThat(store.find(tenant)).isNull()
		} finally {
			execute("ALTER TABLE telemetry_ops.tenant_ingest_summary DROP CONSTRAINT reject_test_tenant")
		}
	}

	@Test
	fun creationAndUpdatesPreserveExistingHistory() {
		val received = Instant.parse("2026-01-01T00:00:00Z")
		store.record(tenant, SummaryOrigin.RECOVERY, received, received.minusSeconds(60))
		execute("UPDATE telemetry_ops.tenant_ingest_summary SET has_pre_ledger_history=true WHERE tenant_id='$tenant'")
		val before = store.find(tenant)
		execute(insertTenant())
		execute("UPDATE enrollment.tenants SET name='renamed' WHERE id='$tenant'")
		assertThat(store.find(tenant)).isEqualTo(before)
	}

	@Test
	fun tenantWriterDoesNotNeedDirectSummaryWritePermission() {
		val role = "tenant_writer_${tenant.toString().replace("-", "")}"
		execute("CREATE ROLE $role")
		execute("GRANT USAGE ON SCHEMA enrollment TO $role")
		execute("GRANT INSERT ON enrollment.tenants TO $role")
		dataSource.connection.use { connection ->
			connection.createStatement().use { statement ->
				statement.execute("SET ROLE $role")
				statement.execute(insertTenant())
			}
		}
		assertThat(store.find(tenant)).isEqualTo(TenantIngestSummary(tenant, null, null, null, false))
	}

	@Test
	fun upgradingDoesNotInventEmptyHistoryForExistingTenants() {
		// 별도 DB에서 V2 → V3 업그레이드를 재현한다. 다른 테스트의 트리거는 건드리지 않는다.
		val database = "legacy_${tenant.toString().replace("-", "")}"
		execute("CREATE DATABASE $database")
		val legacy = PGSimpleDataSource().apply {
			setURL(postgres.jdbcUrl.substringBeforeLast('/') + "/$database")
			user = postgres.username
			password = postgres.password
		}
		try {
			prepareEnrollmentSchema(legacy)
			Flyway.configure().dataSource(legacy).schemas("telemetry_ops").defaultSchema("telemetry_ops")
				.locations("classpath:db/telemetry-ops").target("2").load().migrate()
			legacy.connection.use { it.createStatement().use { statement -> statement.execute(insertTenant()) } }
			TelemetryOpsSchemaMigrator(legacy).migrate()
			assertThat(TenantIngestSummaryStore(legacy).find(tenant)).isNull()
			val fresh = UUID.randomUUID()
			legacy.connection.use { it.createStatement().use { statement -> statement.execute(insertTenant(fresh)) } }
			assertThat(TenantIngestSummaryStore(legacy).find(fresh)).isEqualTo(TenantIngestSummary(fresh, null, null, null, false))
		} finally {
			execute("DROP DATABASE $database")
		}
	}

	private fun insertTenant(id: UUID = tenant) = "INSERT INTO enrollment.tenants (id, name) VALUES ('$id', 'new organization')"

	private fun execute(sql: String) {
		dataSource.connection.use { connection -> connection.createStatement().use { it.execute(sql) } }
	}

	private fun countTenant(): Int = dataSource.connection.use { connection ->
		connection.createStatement().use { statement ->
			statement.executeQuery("SELECT count(*) FROM enrollment.tenants WHERE id='$tenant'").use { it.next(); it.getInt(1) }
		}
	}

	companion object {
		@Container
		@JvmStatic
		val postgres = PostgreSQLContainer("postgres:16-alpine")
	}
}
