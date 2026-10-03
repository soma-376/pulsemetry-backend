package com.team376.pulsemetry.devseed

import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Testcontainers
class SeedTenantOidcTest {
    @Test fun `회사 설정 적용은 검토 재실행과 전체 롤백을 지원하고 회원 sub를 보존한다`() {
        SeedStore.connect(postgres.jdbcUrl, "http://unused.invalid").use { store ->
            store.execute("""CREATE SCHEMA enrollment; CREATE SCHEMA dev_seed;
                CREATE TABLE enrollment.tenants(id uuid PRIMARY KEY,slug text,oidc_issuer text,oidc_client_id text,
                    oidc_client_secret_ref text,sso_enabled boolean DEFAULT false,oidc_require_verified_email boolean DEFAULT false);
                CREATE TABLE enrollment.members(tenant_id uuid,oidc_subject text);
                CREATE TABLE dev_seed.dashboard_datasets(tenant_id uuid,state text);
            """)
            for (name in listOf("A", "B", "C")) {
                store.execute("INSERT INTO enrollment.tenants(id,slug) VALUES(${sql(id(name))},'pulsemetry-seed-${name.lowercase()}')")
                store.execute("INSERT INTO dev_seed.dashboard_datasets VALUES(${sql(id(name))},'ready')")
                store.execute("INSERT INTO enrollment.members VALUES(${sql(id(name))},'preserved-$name')")
            }
            val config = parseTenantOidc(Properties().apply {
                setProperty("PULSEMETRY_COGNITO_ISSUER_URI", "https://cognito-idp.ap-northeast-2.amazonaws.com/ap-northeast-2_test")
                setProperty("PULSEMETRY_COGNITO_CLIENT_ID", "client123")
                setProperty("PULSEMETRY_COGNITO_CLIENT_SECRET", "never-store-this")
            })
            val members = store.query("SELECT * FROM enrollment.members ORDER BY tenant_id")
            assertEquals(3, configureTenantOidc(store, config, false))
            assertEquals("0", store.scalar("SELECT count(*) FROM enrollment.tenants WHERE sso_enabled"))
            store.execute("UPDATE enrollment.tenants SET oidc_client_id='other' WHERE id=${sql(id("C"))}")
            assertFailsWith<IllegalStateException> { configureTenantOidc(store, config, true) }
            assertEquals("0", store.scalar("SELECT count(*) FROM enrollment.tenants WHERE sso_enabled"))
            store.execute("UPDATE enrollment.tenants SET oidc_client_id=NULL")
            assertEquals(3, configureTenantOidc(store, config, true))
            assertEquals(0, configureTenantOidc(store, config, true))
            assertEquals("3", store.scalar("SELECT count(*) FROM enrollment.tenants WHERE sso_enabled AND oidc_client_secret_ref='config:cognito'"))
            assertEquals(members, store.query("SELECT * FROM enrollment.members ORDER BY tenant_id"))
        }
    }
    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("pulsemetry").withUsername("pulsemetry").withPassword("pulsemetry")
    }
}
