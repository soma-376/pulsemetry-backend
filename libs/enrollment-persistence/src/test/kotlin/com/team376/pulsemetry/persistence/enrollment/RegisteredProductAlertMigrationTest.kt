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
import java.util.UUID

/** 허브 ADR 0008: 기존 설정·확인 기록 보존과 신규 규칙 기본 꺼짐을 실제 마이그레이션으로 검증한다. */
class RegisteredProductAlertMigrationTest : AbstractPersistenceIntegrationTest() {
    @Autowired private lateinit var postgres: PostgreSQLContainer

    @Test fun preservesHistoryAndDoesNotEnableReplacement() {
        val database = "alert_probe_" + UUID.randomUUID().toString().replace("-", "")
        fun admin(sql: String) = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use {
            it.createStatement().use { statement -> statement.execute(sql) }
        }
        admin("CREATE DATABASE $database")
        try {
            val source = DriverManagerDataSource(postgres.jdbcUrl.substringBeforeLast("/") + "/$database", postgres.username, postgres.password)
            fun migrate(target: String) = Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .schemas("enrollment").defaultSchema("enrollment").target(target).load().migrate()
            migrate("30")
            val jdbc = JdbcClient.create(source)
            val tenant = UUID.randomUUID()
            val member = UUID.randomUUID()
            val alert = UUID.randomUUID()
            jdbc.sql("INSERT INTO enrollment.tenants(id,name) VALUES (:id,'기존 조직')").param("id", tenant).update()
            jdbc.sql("INSERT INTO enrollment.members(id,tenant_id,email,role) VALUES (:id,:t,'owner@example.test','owner')")
                .param("id", member).param("t", tenant).update()
            for (rule in listOf("spend_spike", "model_not_allowed", "tool_unapproved")) {
                jdbc.sql("INSERT INTO enrollment.organization_alert_rules VALUES (:t,:r,true,2,now(),:m)")
                    .param("t", tenant).param("r", rule).param("m", member).update()
            }
            jdbc.sql("INSERT INTO enrollment.organization_alert_lists VALUES (:t,'allowed_models',1,now(),:m)").param("t", tenant).param("m", member).update()
            jdbc.sql("INSERT INTO enrollment.organization_alert_list_entries VALUES (:t,'allowed_models','old-model')").param("t", tenant).update()
            jdbc.sql("INSERT INTO enrollment.alert_acknowledgements VALUES (:t,:a,1,:m,now())")
                .param("t", tenant).param("a", alert).param("m", member).update()
            fun snapshot(table: String) = jdbc.sql("SELECT row_to_json(t)::text FROM enrollment.$table t").query(String::class.java).list()
            val preserved = listOf("organization_alert_lists", "organization_alert_list_entries", "alert_acknowledgements").associateWith(::snapshot)
            assertThat(migrate("31").migrationsExecuted).isEqualTo(1)
            for ((table, before) in preserved) assertThat(snapshot(table)).isEqualTo(before)
            assertThat(jdbc.sql("SELECT rule_id FROM enrollment.alert_rule_definitions WHERE active ORDER BY position").query(String::class.java).list())
                .containsExactly("spend_spike", "quota_exceeded", "product_not_registered")
            assertThat(jdbc.sql("SELECT rule_id FROM enrollment.organization_alert_rules WHERE enabled").query(String::class.java).list()).containsExactly("spend_spike")
            assertThat(jdbc.sql("SELECT version FROM enrollment.organization_alert_rules WHERE rule_id IN ('model_not_allowed','tool_unapproved')").query(Long::class.java).list()).containsOnly(3L)
            assertThat(jdbc.sql("SELECT count(*) FROM enrollment.organization_alert_rules WHERE rule_id='product_not_registered'").query(Int::class.java).single()).isZero()
            assertThat(migrate("31").migrationsExecuted).isZero()
        } finally {
            admin("DROP DATABASE $database WITH (FORCE)")
        }
    }
}
