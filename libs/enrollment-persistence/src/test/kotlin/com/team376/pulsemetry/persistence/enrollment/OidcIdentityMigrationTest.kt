package com.team376.pulsemetry.persistence.enrollment

import com.team376.pulsemetry.persistence.enrollment.support.AbstractPersistenceIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import java.util.UUID

class OidcIdentityMigrationTest : AbstractPersistenceIntegrationTest() {
    @Autowired lateinit var postgres: PostgreSQLContainer

    @Test fun `V11 데이터는 보존하고 비밀번호 세션만 폐기하며 OIDC 식별 제약을 적용한다`() {
        val database = "v12_probe_" + UUID.randomUUID().toString().replace("-", "")
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
            val member = UUID.randomUUID()
            val session = UUID.randomUUID()
            val installation = UUID.randomUUID()
            val invitation = UUID.randomUUID()
            migrate("11")
            sql("INSERT INTO enrollment.tenants(id,name) VALUES('$tenant','보존할 조직')")
            sql("INSERT INTO enrollment.members(id,tenant_id,email,role,status,password_hash) VALUES('$member','$tenant','owner@example.test','owner','active','old-password-hash')")
            sql("INSERT INTO enrollment.invitations(id,tenant_id,target_member_id,created_by_member_id,code_hash,expires_at) VALUES('$invitation','$tenant','$member','$member','${"i".repeat(64)}',now()+interval '1 day')")
            sql("INSERT INTO enrollment.installations(id,tenant_id,member_id,invitation_id,platform) VALUES('$installation','$tenant','$member','$invitation','macos')")
            sql("INSERT INTO enrollment.user_sessions(id,member_id,manifest_revision,created_at,expires_at) VALUES('$session','$member',0,now(),now()+interval '30 days')")
            sql("INSERT INTO enrollment.user_refresh_tokens(token_hash,session_id,issued_at) VALUES('${"r".repeat(64)}','$session',now())")
            sql("INSERT INTO enrollment.user_authorization_codes(code_hash,member_id,redirect_uri,code_challenge,expires_at) VALUES('${"c".repeat(64)}','$member','http://127.0.0.1:12345/callback','${"p".repeat(43)}',now()+interval '1 minute')")
            val before = jdbc.sql("SELECT id,tenant_id,email,role,status,created_at,updated_at FROM enrollment.members").query().singleRow()
            migrate("12")
            assertThat(jdbc.sql("SELECT id,tenant_id,email,role,status,created_at,updated_at FROM enrollment.members").query().singleRow()).isEqualTo(before)
            assertThat(jdbc.sql("SELECT id FROM enrollment.installations").query(UUID::class.java).single()).isEqualTo(installation)
            assertThat(jdbc.sql("SELECT count(*) FROM enrollment.user_sessions WHERE revoked_at IS NOT NULL").query(Long::class.java).single()).isEqualTo(1)
            assertThat(jdbc.sql("SELECT count(*) FROM enrollment.user_authorization_codes WHERE used_at IS NOT NULL").query(Long::class.java).single()).isEqualTo(1)
            assertThat(jdbc.sql("SELECT column_name FROM information_schema.columns WHERE table_schema='enrollment' AND table_name='members'").query(String::class.java).list())
                .contains("oidc_issuer", "oidc_subject").doesNotContain("password_hash")
            assertThat(jdbc.sql("SELECT count(*) FROM enrollment.members WHERE oidc_issuer IS NULL AND oidc_subject IS NULL").query(Long::class.java).single()).isEqualTo(1)
            assertThatThrownBy { sql("UPDATE enrollment.members SET oidc_issuer='https://idp.example.test'") }.isInstanceOf(DataIntegrityViolationException::class.java)
            sql("UPDATE enrollment.members SET oidc_issuer='https://idp.example.test',oidc_subject='subject-1'")
            assertThatThrownBy { sql("INSERT INTO enrollment.members(tenant_id,email,oidc_issuer,oidc_subject) VALUES('$tenant','other@example.test','https://idp.example.test','subject-1')") }
                .isInstanceOf(DataIntegrityViolationException::class.java)
            assertThatThrownBy { sql("UPDATE enrollment.members SET oidc_subject=' '") }.isInstanceOf(DataIntegrityViolationException::class.java)
        } finally { admin("DROP DATABASE $database WITH (FORCE)") }
    }
}
