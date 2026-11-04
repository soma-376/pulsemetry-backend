package com.team376.pulsemetry.persistence.enrollment

import com.team376.pulsemetry.persistence.enrollment.installation.CollectionMode
import com.team376.pulsemetry.persistence.enrollment.installation.InstallationReport
import com.team376.pulsemetry.persistence.enrollment.installation.InstallationLossStore
import com.team376.pulsemetry.persistence.enrollment.support.AbstractPersistenceIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import java.sql.Timestamp
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

class InstallationLossMigrationTest : AbstractPersistenceIntegrationTest() {
    @Autowired private lateinit var postgres: PostgreSQLContainer

    @Test fun `기존 누계는 기준만 이관하고 역사 실행은 재합산하지 않는다`() {
        val database = "loss_probe_" + UUID.randomUUID().toString().replace("-", "")
        fun admin(sql: String) = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use {
            it.createStatement().use { statement -> statement.execute(sql) }
        }
        admin("CREATE DATABASE $database")
        try {
            val source = DriverManagerDataSource(postgres.jdbcUrl.substringBeforeLast("/") + "/$database", postgres.username, postgres.password)
            fun migrate(target: String) = Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .schemas("enrollment").defaultSchema("enrollment").target(target).load().migrate()
            migrate("33")
            val jdbc = JdbcClient.create(source)
            val tenant = UUID.randomUUID()
            val member = UUID.randomUUID()
            val invitation = UUID.randomUUID()
            val installation = UUID.randomUUID()
            jdbc.sql("INSERT INTO enrollment.tenants(id,name) VALUES (:id,'전환 조직')").param("id", tenant).update()
            jdbc.sql("INSERT INTO enrollment.members(id,tenant_id,email,role) VALUES (:id,:t,'owner@example.test','owner')")
                .param("id", member).param("t", tenant).update()
            jdbc.sql("""INSERT INTO enrollment.invitations(id,tenant_id,target_member_id,created_by_member_id,code_hash,expires_at)
                VALUES (:id,:t,:m,:m,:hash,now()+interval '1 day')""")
                .param("id", invitation).param("t", tenant).param("m", member).param("hash", "a".repeat(64)).update()
            jdbc.sql("INSERT INTO enrollment.installations(id,tenant_id,member_id,invitation_id,platform) VALUES (:id,:t,:m,:i,'macos')")
                .param("id", installation).param("t", tenant).param("m", member).param("i", invitation).update()
            jdbc.sql("""INSERT INTO enrollment.installation_heartbeats(installation_id,received_at,run_id,daemon_version,architecture,
                applied_config_revision,mode,forwarding,delivered,lost,pending) VALUES (:id,now(),'current','1','arm64',1,'local',true,0,8,0)""")
                .param("id", installation).update()
            jdbc.sql("""INSERT INTO enrollment.installation_collection_segments(id,installation_id,run_id,from_at,to_at,lost)
                VALUES (:id,:i,'old',now()-interval '2 hours',now()-interval '1 hour',99)""")
                .param("id", UUID.randomUUID()).param("i", installation).update()
            assertThat(migrate("34").migrationsExecuted).isEqualTo(1)
            fun count() = jdbc.sql("SELECT COALESCE(sum(payload_count),0) FROM enrollment.installation_loss_events").query(Long::class.java).single()
            assertThat(count()).isZero()
            val store = InstallationLossStore(jdbc, Duration.ofDays(1))
            val tx = TransactionTemplate(DataSourceTransactionManager(source))
            fun report(run: String, lost: Long) = tx.executeWithoutResult {
                jdbc.sql("SELECT id FROM enrollment.installations WHERE id=:id FOR UPDATE").param("id", installation).query(UUID::class.java).single()
                val now = Instant.now()
                store.record(installation, InstallationReport(now,"1","arm64",run,1,CollectionMode.LOCAL,null,true,0,lost,0,null), now)
            }
            report("current", 8)
            report("old", 999)
            assertThat(count()).isZero()
            report("current", 10)
            report("new", 3)
            assertThat(count()).isEqualTo(5)
            assertThat(jdbc.sql("SELECT historical FROM enrollment.installation_loss_runs WHERE run_id='old'").query(Boolean::class.javaObjectType).single()).isTrue()
            fun script(name: String) = source.connection.use { connection ->
                connection.createStatement().use { it.execute(Files.readString(Path.of("../../scripts/$name.sql"))) }
            }
            // 실제 운영 스크립트를 실행한다. 구 writer가 기록한 누계를 재배포 때 신규 손실로 세면 안 된다.
            script("installation-loss-pause")
            assertThatThrownBy { report("current", 50) }.isInstanceOf(IllegalStateException::class.java)
            jdbc.sql("UPDATE enrollment.installation_heartbeats SET lost=99 WHERE installation_id=:id").param("id", installation).update()
            script("installation-loss-resume")
            report("current", 99)
            assertThat(count()).isEqualTo(5)
            report("current", 100)
            assertThat(count()).isEqualTo(6)
            assertThat(jdbc.sql("SELECT count(*) FROM enrollment.installation_loss_tracking WHERE stopped_at IS NOT NULL").query(Int::class.java).single()).isEqualTo(1)
            // history-retention=1일이어도 168시간 창과 상세 페이지 유효기간 10분은 보존한다.
            val kept = UUID.randomUUID()
            val removed = UUID.randomUUID()
            for ((id, minutes) in listOf(kept to 5L, removed to 11L)) {
                jdbc.sql("""INSERT INTO enrollment.installation_loss_events(id,tenant_id,installation_id,run_id,payload_count,observed_at)
                    VALUES (:id,:t,:i,'retention',1,:at)""").param("id", id).param("t", tenant).param("i", installation)
                    .param("at", Timestamp.from(Instant.now().minus(Duration.ofHours(168)).minusSeconds(minutes * 60))).update()
            }
            report("current", 100)
            assertThat(jdbc.sql("SELECT id FROM enrollment.installation_loss_events WHERE id IN (:kept,:removed)")
                .param("kept", kept).param("removed", removed).query(UUID::class.java).list()).containsExactly(kept)
            assertThat(migrate("34").migrationsExecuted).isZero()
        } finally { admin("DROP DATABASE $database WITH (FORCE)") }
    }
}
