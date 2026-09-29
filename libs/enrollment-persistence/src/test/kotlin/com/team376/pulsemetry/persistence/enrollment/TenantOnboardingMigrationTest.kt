package com.team376.pulsemetry.persistence.enrollment

import com.team376.pulsemetry.persistence.enrollment.support.AbstractPersistenceIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

class TenantOnboardingMigrationTest : AbstractPersistenceIntegrationTest() {
    @Autowired private lateinit var postgres: PostgreSQLContainer

    @Test
    fun `V8은 기존 완료 시각을 조직으로 보존하고 미완료는 false로 유지한다`() {
        val database = "onboarding_probe_" + UUID.randomUUID().toString().replace("-", "")
        fun admin(sql: String) = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute(sql) }
        }
        admin("CREATE DATABASE $database")
        try {
            val source = DriverManagerDataSource("jdbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/$database", postgres.username, postgres.password)
            fun migrate(target: String) = Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .schemas("enrollment").defaultSchema("enrollment").target(target).load().migrate()
            migrate("7")
            val jdbc = JdbcClient.create(source)
            val complete = UUID.randomUUID()
            val pending = UUID.randomUUID()
            val untouched = UUID.randomUUID()
            for (tenant in listOf(complete, pending, untouched)) {
                jdbc.sql("INSERT INTO enrollment.tenants(id,name) VALUES (:id,'migration test')").param("id", tenant).update()
            }
            for (tenant in listOf(complete, pending)) {
                val actor = UUID.randomUUID()
                jdbc.sql("INSERT INTO enrollment.members(id,tenant_id,email,role) VALUES (:id,:tenant,'owner@example.test','owner')")
                    .param("id", actor).param("tenant", tenant).update()
                jdbc.sql("""INSERT INTO enrollment.organization_onboarding(tenant_id,policy_confirmed_at,policy_confirmed_by,completed_at,completed_by)
                    VALUES (:tenant,'2026-09-01T00:00:00Z',:actor,
                        CASE WHEN :complete THEN '2026-09-02T03:04:05.123456Z'::timestamptz ELSE NULL END,
                        CASE WHEN :complete THEN :actor ELSE NULL END)""")
                    .param("tenant", tenant).param("actor", actor).param("complete", tenant == complete).update()
            }
            migrate("8")
            val rows = jdbc.sql("SELECT id,onboarding_completed,onboarding_completed_at FROM enrollment.tenants")
                .query { r, _ -> r.getObject(1, UUID::class.java) to (r.getBoolean(2) to r.getTimestamp(3)?.toInstant()) }.list().toMap()
            assertThat(rows.getValue(complete)).isEqualTo(true to Instant.parse("2026-09-02T03:04:05.123456Z"))
            assertThat(rows.getValue(pending)).isEqualTo(false to null)
            assertThat(rows.getValue(untouched)).isEqualTo(false to null)
            assertThat(jdbc.sql("SELECT count(*) FROM enrollment.organization_onboarding WHERE completed_by IS NOT NULL").query(Int::class.java).single()).isEqualTo(1)
            assertThat(jdbc.sql("SELECT count(*) FROM information_schema.columns WHERE table_schema='enrollment' AND table_name='organization_onboarding' AND column_name='completed_at'")
                .query(Int::class.java).single()).isZero()
            jdbc.sql("UPDATE enrollment.tenants SET onboarding_completed_at='2026-09-03T00:00:00Z' WHERE id=:id").param("id", pending).update()
            assertThat(jdbc.sql("SELECT onboarding_completed FROM enrollment.tenants WHERE id=:id").param("id", pending).query(Boolean::class.java).single()).isTrue()
            jdbc.sql("UPDATE enrollment.tenants SET onboarding_completed_at=NULL WHERE id=:id").param("id", pending).update()
            assertThat(jdbc.sql("SELECT onboarding_completed FROM enrollment.tenants WHERE id=:id").param("id", pending).query(Boolean::class.java).single()).isFalse()
        } finally {
            admin("DROP DATABASE $database WITH (FORCE)")
        }
    }
}
