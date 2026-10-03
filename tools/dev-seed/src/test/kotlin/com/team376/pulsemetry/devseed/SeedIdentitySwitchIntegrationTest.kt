package com.team376.pulsemetry.devseed

import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Testcontainers
class SeedIdentitySwitchIntegrationTest {
    @Test fun `실제 PG에서 신원만 원자 변경하고 세션 폐기 재실행 복구를 보장한다`() {
        SeedStore.connect(postgres.jdbcUrl, "http://unused.invalid").use { store ->
            store.execute("""CREATE SCHEMA enrollment; CREATE SCHEMA dev_seed;
                CREATE TABLE enrollment.tenants(id uuid PRIMARY KEY, slug text, oidc_issuer text, sso_enabled boolean DEFAULT false);
                CREATE TABLE enrollment.members(id uuid PRIMARY KEY, tenant_id uuid, email text, oidc_subject text, role text);
                CREATE TABLE dev_seed.dashboard_datasets(tenant_id uuid PRIMARY KEY, state text);
                CREATE TABLE enrollment.user_sessions(member_id uuid, revoked_at timestamptz);
                CREATE TABLE enrollment.user_authorization_codes(member_id uuid, used_at timestamptz);
            """)
            val users = listOf("A" to 0, "A" to 1, "B" to 0, "C" to 0, "C" to 1).map { (name, index) ->
                SeedIdentity(name, index, "${if (index == 0) "owner" else "admin"}@seed-${name.lowercase()}.example.test", "subject-$name-$index")
            }
            for (name in listOf("A", "B", "C")) {
                store.execute("INSERT INTO enrollment.tenants(id,slug,oidc_issuer) VALUES (${sql(id(name))},'pulsemetry-seed-${name.lowercase()}','https://previous-idp.example.test')")
                store.execute("INSERT INTO dev_seed.dashboard_datasets VALUES (${sql(id(name))},'ready')")
            }
            for (user in users) {
                store.execute("INSERT INTO enrollment.members VALUES (${sql(user.memberId)},${sql(id(user.scenario))},${sql(user.email)},${sql("old-${user.subject}")},'owner')")
                store.execute("INSERT INTO enrollment.user_sessions VALUES (${sql(user.memberId)},NULL)")
                store.execute("INSERT INTO enrollment.user_authorization_codes VALUES (${sql(user.memberId)},NULL)")
            }
            val manifest = SeedIdentityManifest("https://cognito-idp.ap-northeast-2.amazonaws.com/ap-northeast-2_test", users)
            val adapter = ComposeIdentityStore(store)
            val snapshot = parseIdentitySnapshot(identitySnapshot(adapter, manifest), manifest)
            val changes = identityChanges(manifest, snapshot)
            val original = store.query("SELECT * FROM enrollment.members ORDER BY id")
            assertEquals(5, switchIdentities(adapter, changes, false))
            assertEquals(original, store.query("SELECT * FROM enrollment.members ORDER BY id"))
            store.execute("UPDATE enrollment.members SET email='changed@example.test' WHERE id=${sql(users.last().memberId)}")
            assertFailsWith<IllegalStateException> { switchIdentities(adapter, changes, true) }
            assertEquals("0", store.scalar("SELECT count(*) FROM enrollment.tenants WHERE oidc_issuer=${sql(manifest.issuer)}"))
            store.execute("UPDATE enrollment.members SET email=${sql(users.last().email)} WHERE id=${sql(users.last().memberId)}")
            assertEquals(5, switchIdentities(adapter, changes, true))
            assertEquals("5", store.scalar("SELECT count(*) FROM enrollment.user_sessions WHERE revoked_at IS NOT NULL"))
            assertEquals("5", store.scalar("SELECT count(*) FROM enrollment.user_authorization_codes WHERE used_at IS NOT NULL"))
            assertEquals(0, switchIdentities(adapter, changes, true))
            assertEquals(5, switchIdentities(adapter, identityChanges(manifest, snapshot, restore = true), true))
            assertEquals(original, store.query("SELECT * FROM enrollment.members ORDER BY id"))
            store.execute("UPDATE enrollment.members SET oidc_subject=NULL; UPDATE enrollment.tenants SET oidc_issuer=NULL")
            val empty = parseIdentitySnapshot(identitySnapshot(adapter, manifest), manifest)
            assertEquals(5, switchIdentities(adapter, identityChanges(manifest), true))
            assertEquals(5, switchIdentities(adapter, identityChanges(manifest, empty, restore = true), true))
            assertEquals("5", store.scalar("SELECT count(*) FROM enrollment.members m JOIN enrollment.tenants t ON t.id=m.tenant_id WHERE t.oidc_issuer IS NULL AND m.oidc_subject IS NULL"))
        }
    }
    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("pulsemetry").withUsername("pulsemetry").withPassword("pulsemetry")
    }
}
