package com.team376.pulsemetry.persistence.enrollment

import com.team376.pulsemetry.persistence.enrollment.management.VendorCatalog
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

class VendorCatalogMigrationTest : AbstractPersistenceIntegrationTest() {
    @Autowired private lateinit var postgres: PostgreSQLContainer

    @Test fun `V10 초기화와 V11 좌석 보정은 계약 및 카탈로그 편집을 보존한다`() {
        val database = "catalog_probe_" + UUID.randomUUID().toString().replace("-", "")
        fun admin(sql: String) = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use {
            it.createStatement().use { statement -> statement.execute(sql) }
        }
        admin("CREATE DATABASE $database")
        try {
            val source = DriverManagerDataSource("jdbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/$database", postgres.username, postgres.password)
            fun migrate(target: String) = Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .schemas("enrollment").defaultSchema("enrollment").target(target).load().migrate()
            migrate("9")
            val jdbc = JdbcClient.create(source)
            val tenant = UUID.randomUUID()
            val member = UUID.randomUUID()
            jdbc.sql("INSERT INTO enrollment.tenants(id,name) VALUES (:id,'기존 조직')").param("id", tenant).update()
            jdbc.sql("INSERT INTO enrollment.members(id,tenant_id,email,role) VALUES (:id,:tenant,'owner@example.test','owner')").param("id", member).param("tenant", tenant).update()
            jdbc.sql("INSERT INTO enrollment.managed_vendors(tenant_id,vendor_id,kind,source,created_at) VALUES (:tenant,'existing','claude_team','manual',now())").param("tenant", tenant).update()
            jdbc.sql("INSERT INTO enrollment.vendor_contract_versions VALUES (:tenant,'existing',1,'기존 계약','{\"planId\":\"team\",\"monthlySeatFeeUsd\":\"123\"}'::jsonb,false,now(),:member)")
                .param("tenant", tenant).param("member", member).update()
            fun history() = jdbc.sql("SELECT row_to_json(c)::text FROM enrollment.vendor_contract_versions c").query(String::class.java).single()
            val before = history()
            assertThat(migrate("10").migrationsExecuted).isEqualTo(1)
            assertThat(history()).isEqualTo(before)
            assertThat(VendorCatalog(jdbc).snapshot().products).hasSize(6)
            assertThat(jdbc.sql("SELECT count(*) FROM enrollment.vendor_catalog_vendors").query(Int::class.java).single()).isEqualTo(6)

            jdbc.sql("UPDATE enrollment.vendor_catalog_products SET display_name='DB에서 변경' WHERE id='claude_team'").update()
            assertThat(migrate("10").migrationsExecuted).isZero()
            assertThat(VendorCatalog(jdbc).find("claude_team")!!.displayName).isEqualTo("DB에서 변경")
            assertThat(history()).isEqualTo(before)
            val beforeCorrection = VendorCatalog(jdbc).snapshot()
            assertThat(beforeCorrection.products.single { it.id == "openai_biz" }.allowsSeatTiers).isFalse()
            assertThat(migrate("11").migrationsExecuted).isEqualTo(1)
            val corrected = VendorCatalog(jdbc).snapshot()
            assertThat(corrected.products).isEqualTo(beforeCorrection.products.map {
                if (it.id == "openai_biz") it.copy(allowsSeatTiers = true) else it
            })
            assertThat(corrected.version).isNotEqualTo(beforeCorrection.version)
            assertThat(history()).isEqualTo(before)
            assertThat(migrate("11").migrationsExecuted).isZero()
            assertThat(VendorCatalog(jdbc).snapshot()).isEqualTo(corrected)
            assertThatThrownBy {
                jdbc.sql("INSERT INTO enrollment.vendor_catalog_plans(product_id,id,display_name) VALUES ('missing','test','잘못된 참조')").update()
            }.isInstanceOf(DataIntegrityViolationException::class.java)
            assertThatThrownBy {
                jdbc.sql("INSERT INTO enrollment.vendor_catalog_plans(product_id,id,display_name) VALUES ('claude_team','team','중복')").update()
            }.isInstanceOf(DataIntegrityViolationException::class.java)
        } finally {
            admin("DROP DATABASE $database WITH (FORCE)")
        }
    }
}
