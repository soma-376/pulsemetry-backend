package com.team376.pulsemetry.persistence.enrollment

import com.team376.pulsemetry.persistence.enrollment.support.AbstractPersistenceIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import java.util.UUID

class TenantOidcMigrationTest : AbstractPersistenceIntegrationTest() {
    @Autowired lateinit var postgres: PostgreSQLContainer

    @ParameterizedTest @ValueSource(booleans = [false, true])
    fun `V29은 회사 issuer와 회원 sub를 보존하고 혼재된 issuer는 거부한다`(mixed: Boolean) {
        val database = "v13_probe_" + UUID.randomUUID().toString().replace("-", "")
        fun admin(sql: String) = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use {
            it.createStatement().use { statement -> statement.execute(sql) }
        }
        admin("CREATE DATABASE $database")
        try {
            val source = DriverManagerDataSource("jdbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/$database", postgres.username, postgres.password)
            val jdbc = JdbcClient.create(source)
            fun migrate(version: String) = Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .schemas("enrollment").defaultSchema("enrollment").target(version).load().migrate()
            fun sql(statement: String) { jdbc.sql(statement).update() }
            val tenant = UUID.randomUUID()
            migrate("28")
            sql("INSERT INTO enrollment.tenants(id,name) VALUES('$tenant','기존 회사')")
            sql("INSERT INTO enrollment.members(tenant_id,email,role,oidc_issuer,oidc_subject) VALUES('$tenant','a@example.test','owner','https://idp.example.test','sub-a')")
            sql("INSERT INTO enrollment.members(tenant_id,email,oidc_issuer,oidc_subject) VALUES('$tenant','b@example.test','${if (mixed) "https://other.example.test" else "https://idp.example.test"}','sub-b')")
            val before = jdbc.sql("SELECT id,tenant_id,email,role,oidc_subject FROM enrollment.members ORDER BY id").query().listOfRows()
            if (mixed) {
                assertThatThrownBy { migrate("29") }.hasStackTraceContaining("여러 OIDC issuer")
                assertThat(jdbc.sql("SELECT count(*) FROM enrollment.members WHERE oidc_issuer IS NOT NULL").query(Long::class.java).single()).isEqualTo(2)
                return
            }
            migrate("29")
            assertThat(jdbc.sql("SELECT id,tenant_id,email,role,oidc_subject FROM enrollment.members ORDER BY id").query().listOfRows()).isEqualTo(before)
            assertThat(jdbc.sql("SELECT oidc_issuer FROM enrollment.tenants").query(String::class.java).single()).isEqualTo("https://idp.example.test")
            assertThat(jdbc.sql("SELECT sso_enabled FROM enrollment.tenants").query(Boolean::class.java).single()).isFalse()
            assertThat(jdbc.sql("SELECT column_name FROM information_schema.columns WHERE table_schema='enrollment' AND table_name='members'").query(String::class.java).list()).doesNotContain("oidc_issuer")
            assertThatThrownBy { sql("INSERT INTO enrollment.members(tenant_id,email,oidc_subject) VALUES('$tenant','duplicate@example.test','sub-a')") }.hasMessageContaining("uq_members_tenant_oidc_subject")
            val other = UUID.randomUUID()
            sql("INSERT INTO enrollment.tenants(id,name) VALUES('$other','다른 회사')")
            sql("INSERT INTO enrollment.members(tenant_id,email,oidc_subject) VALUES('$other','a@example.test','sub-a')")
            assertThatThrownBy { sql("UPDATE enrollment.tenants SET sso_enabled=true WHERE id='$tenant'") }.hasMessageContaining("ck_tenants_sso_configuration")
        } finally { admin("DROP DATABASE $database WITH (FORCE)") }
    }
}
