package com.team376.pulsemetry.devseed

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal fun companySeedText(company: String): String = encode(mapOf(
    "version" to 2, "company" to company,
    "issuer" to "https://cognito-idp.ap-northeast-2.amazonaws.com/ap-northeast-2_test$company",
    "clientId" to "client$company", "clientSecretRef" to "config:cognito-${company.lowercase()}"))

class CompanyOidcParserTest {
    @TempDir lateinit var directory: Path

    @Test fun `회사 파일 누락과 잘못된 회사 또는 비밀 필드를 거부한다`() {
        assertEquals(emptyList(), loadCompanyOidcSeeds(directory, listOf("C")))
        assertFailsWith<IllegalArgumentException> { loadCompanyOidcSeeds(directory, listOf("A")) }
        val a = companySeedText("A")
        for (bad in listOf(a.replace("\"version\":2", "\"version\":1"),
            a.replace("\"version\":2", "\"clientSecret\":\"forbidden\",\"version\":2"),
            a.replace("\"version\":2", "\"users\":[],\"version\":2"),
            a.replace("config:cognito-a", "config:cognito-b"),
            a.replace("https://cognito-idp.ap-northeast-2.amazonaws.com/", "http://localhost/"))) {
            assertFailsWith<IllegalArgumentException> { parseCompanyOidcSeed(bad) }
        }
        Files.createDirectories(directory.resolve("company-a"))
        Files.writeString(directory.resolve("company-a/cognito-seed.json"), companySeedText("B"))
        assertFailsWith<IllegalArgumentException> { loadCompanyOidcSeeds(directory, listOf("A")) }
        Files.writeString(directory.resolve("company-a/cognito-seed.json"), a)
        assertEquals(listOf("A"), loadCompanyOidcSeeds(directory, listOf("A", "C")).map { it.company })
    }
}

@Testcontainers
class SeedCompanyOidcTest {
    private val seeds = listOf("A", "B").map { parseCompanyOidcSeed(companySeedText(it)) }

    @BeforeEach fun prepare() {
        SeedStore.connect(postgres.jdbcUrl, "http://unused.invalid").use { store ->
            store.execute("DROP SCHEMA IF EXISTS enrollment CASCADE; DROP SCHEMA IF EXISTS dev_seed CASCADE")
        }
        Flyway.configure().dataSource(postgres.jdbcUrl, "pulsemetry", "pulsemetry")
            .schemas("enrollment").defaultSchema("enrollment").locations("classpath:db/migration").load().migrate()
        SeedStore.connect(postgres.jdbcUrl, "http://unused.invalid").use { store ->
            store.execute("CREATE SCHEMA dev_seed; CREATE TABLE dev_seed.dashboard_datasets(tenant_id uuid PRIMARY KEY,state text)")
            for (name in listOf("A", "B", "C")) {
                store.execute("INSERT INTO enrollment.tenants(id,slug,name) VALUES(${sql(id(name))},'pulsemetry-seed-${name.lowercase()}','Company $name')")
                store.execute("INSERT INTO dev_seed.dashboard_datasets VALUES(${sql(id(name))},'ready')")
            }
            for (user in listOf(SeedIdentity("A", 0, "owner@seed-a.example.test", "unused"),
                SeedIdentity("B", 0, "owner@seed-b.example.test", "unused"))) {
                store.execute("INSERT INTO enrollment.members(id,tenant_id,email,role) VALUES(${sql(user.memberId)},${sql(id(user.scenario))},${sql(user.email)},'owner')")
            }
        }
    }

    @Test fun `최초 자동 주입 후 재실행은 세션과 운영 중 변경을 보존한다`() {
        SeedStore.connect(postgres.jdbcUrl, "http://unused.invalid").use { store ->
            val identities = store.query("SELECT id,tenant_id,email,role,status FROM enrollment.members ORDER BY id")
            assertEquals(2, seedCompanyOidc(store, seeds))
            assertEquals("0", store.scalar("SELECT count(*) FROM enrollment.members WHERE oidc_subject IS NOT NULL"))
            assertEquals("2", store.scalar("SELECT count(*) FROM enrollment.tenants WHERE sso_enabled AND oidc_require_verified_email"))
            assertEquals(identities, store.query("SELECT id,tenant_id,email,role,status FROM enrollment.members ORDER BY id"))
            val owner = sql(id("A/member/0"))
            store.execute("INSERT INTO enrollment.user_sessions(id,member_id,manifest_revision,created_at,expires_at) VALUES(${sql(id("session"))},$owner,1,now(),now()+interval '1 hour')")
            store.execute("UPDATE enrollment.tenants SET sso_enabled=false,onboarding_completed_at=now() WHERE id=${sql(id("A"))}")
            assertEquals(0, seedCompanyOidc(store, seeds))
            assertEquals("f", store.scalar("SELECT sso_enabled FROM enrollment.tenants WHERE id=${sql(id("A"))}"))
            assertEquals("1", store.scalar("SELECT count(*) FROM enrollment.user_sessions WHERE revoked_at IS NULL"))
            assertEquals("t", store.scalar("SELECT onboarding_completed FROM enrollment.tenants WHERE id=${sql(id("A"))}"))
            assertEquals("0", store.scalar("SELECT count(*) FROM enrollment.tenants WHERE id=${sql(id("C"))} AND oidc_issuer IS NOT NULL"))
        }
    }

    @Test fun `뒤 회사의 다른 연결이 발견되면 앞 회사와 회원 변경도 롤백한다`() {
        SeedStore.connect(postgres.jdbcUrl, "http://unused.invalid").use { store ->
            store.execute("UPDATE enrollment.tenants SET oidc_issuer='https://other.example.test' WHERE id=${sql(id("B"))}")
            assertFailsWith<IllegalStateException> { seedCompanyOidc(store, seeds) }
            assertEquals("0", store.scalar("SELECT count(*) FROM enrollment.members WHERE oidc_subject IS NOT NULL"))
            assertEquals("0", store.scalar("SELECT count(*) FROM enrollment.tenants WHERE sso_enabled"))
        }
    }

    @Test fun `로그인으로 연결된 sub와 변경된 회원 정보를 재실행해도 보존한다`() {
        SeedStore.connect(postgres.jdbcUrl, "http://unused.invalid").use { store ->
            assertEquals(2, seedCompanyOidc(store, seeds))
            val member = sql(id("A/member/0"))
            store.execute("UPDATE enrollment.members SET email='changed@example.test',oidc_subject='login-linked-sub' WHERE id=$member")
            val before = store.query("SELECT * FROM enrollment.members ORDER BY id")
            assertEquals(0, seedCompanyOidc(store, seeds))
            assertEquals(before, store.query("SELECT * FROM enrollment.members ORDER BY id"))
        }
    }

    @Test fun `회사 설정 없는 기존 sub를 새 issuer에 묵시적으로 연결하지 않는다`() {
        SeedStore.connect(postgres.jdbcUrl, "http://unused.invalid").use { store ->
            store.execute("UPDATE enrollment.members SET oidc_subject='existing' WHERE id=${sql(id("A/member/0"))}")
            assertFailsWith<IllegalStateException> { seedCompanyOidc(store, seeds) }
            assertEquals("existing", store.scalar("SELECT oidc_subject FROM enrollment.members WHERE id=${sql(id("A/member/0"))}"))
        }
    }

    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("pulsemetry").withUsername("pulsemetry").withPassword("pulsemetry")
    }
}
