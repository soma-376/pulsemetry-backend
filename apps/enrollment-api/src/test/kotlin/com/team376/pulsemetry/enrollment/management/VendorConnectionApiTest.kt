package com.team376.pulsemetry.enrollment.management

import com.team376.pulsemetry.connector.vendor.BillingReader
import com.team376.pulsemetry.connector.vendor.Capability
import com.team376.pulsemetry.connector.vendor.ConnectionTarget
import com.team376.pulsemetry.connector.vendor.ConnectorDescriptor
import com.team376.pulsemetry.connector.vendor.ConnectorDescriptors
import com.team376.pulsemetry.connector.vendor.ConnectorFailure
import com.team376.pulsemetry.connector.vendor.ControlResult
import com.team376.pulsemetry.connector.vendor.ControlStatus
import com.team376.pulsemetry.connector.vendor.SeatConnector
import com.team376.pulsemetry.connector.vendor.SeatRelease
import com.team376.pulsemetry.connector.vendor.SeatRestore
import com.team376.pulsemetry.connector.vendor.VendorSeat
import com.team376.pulsemetry.enrollment.auth.AbstractUserAuthApiTest
import com.team376.pulsemetry.enrollment.auth.AuthClockConfig
import com.team376.pulsemetry.enrollment.support.EnrollmentTestData
import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import tools.jackson.databind.JsonNode
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 벤더 연결 명령 (ADR 0048 §6). 기대값은 ADR 의 문장에서 쓴다 — 커넥터는 제품·계약 플랜으로 고르고 이 배포에 구현이 없으면 만들 수 없다,
 * 자격증명은 암호문으로만 남고 응답·로그·멱등 기록에 없다, 교체는 확인 상태를 되돌리고 대상이 바뀌면 동기화 기록을 비운다, 삭제·보관은 암호문을 지운다,
 * 확인은 트랜잭션 밖에서 부르고 그 사이 연결이 바뀌면 결과를 쓰지 않는다.
 *
 * 이 테스트 배포는 Copilot 과 Claude Enterprise 의 가짜 구현만 조립한다(Cursor Enterprise 는 설명만 있고 구현이 없다). 실제 벤더 호출은 하지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["pulsemetry.user-auth.enabled=true", "pulsemetry.management.enabled=true",
        "pulsemetry.management.onboarding-otlp-endpoint=https://ingest.example.test",
        "pulsemetry.management.response-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "pulsemetry.user-auth.allowed-origins=http://localhost:3000",
        "pulsemetry.vendor-connections.enabled=true",
        "pulsemetry.vendor-connections.credential-keys.k1=BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=",
        "pulsemetry.vendor-connections.credential-key-id=k1"])
@AutoConfigureMockMvc
@Import(PostgresContainerConfig::class, EnrollmentTestData::class, AuthClockConfig::class, VendorConnectionApiTest.FakeConnectors::class)
@ExtendWith(OutputCaptureExtension::class)
class VendorConnectionApiTest : AbstractUserAuthApiTest() {

    /** 확인 호출만 흉내 낸다 — 받은 대상을 남기고, 정한 실패를 던진다. */
    class FakeConnector(override val descriptor: ConnectorDescriptor) : SeatConnector {
        @Volatile var failure: ConnectorFailure.Kind? = null
        @Volatile var during: (() -> Unit)? = null
        val seen = CopyOnWriteArrayList<ConnectionTarget>()
        override fun verify(target: ConnectionTarget) {
            seen += target
            during?.invoke()
            failure?.let { throw ConnectorFailure(it) }
        }
        override fun listSeats(target: ConnectionTarget): List<VendorSeat> = emptyList()
        override val release: SeatRelease? = SeatRelease { _, _ -> ControlResult(ControlStatus.COMPLETED) }.takeIf { Capability.SEAT_RELEASE in descriptor.capabilities }
        override val restore: SeatRestore? = SeatRestore { _, _ -> ControlResult(ControlStatus.COMPLETED) }.takeIf { Capability.SEAT_RESTORE in descriptor.capabilities }
        override val billing: BillingReader? = BillingReader { _, _, _ -> emptyList() }.takeIf { Capability.BILLING in descriptor.capabilities }
        fun reset() { failure = null; during = null; seen.clear() }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class FakeConnectors {
        @Bean fun copilotConnector() = FakeConnector(ConnectorDescriptors.COPILOT)
        @Bean fun claudeConnector() = FakeConnector(ConnectorDescriptors.CLAUDE_ENTERPRISE)
    }

    @Autowired private lateinit var copilotConnector: FakeConnector
    @Autowired private lateinit var claudeConnector: FakeConnector

    private val secret = "fake-vendor-credential-" + "Zq9".repeat(12)
    private val secretVariants get() = listOf(secret, Base64.getEncoder().encodeToString(secret.toByteArray()))

    @AfterEach fun resetFakes() { copilotConnector.reset(); claudeConnector.reset() }

    private fun json(response: HttpResponse<String>): JsonNode = mapper.readTree(response.body())

    private fun vendor(kind: String, plan: String, token: String): JsonNode {
        val contract = mapOf("planId" to plan, "effectiveFrom" to "2026-09-09", "tiers" to listOf(mapOf("label" to "표준", "seats" to 3, "monthlyFeePerSeatUsd" to "19")))
        val created = manage("POST", "/vendors", mapOf("kind" to kind, "displayName" to kind, "contract" to contract), token)
        assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(201)
        return json(created).path("vendor")
    }

    private fun connect(vendorId: String, token: String, expectedVersion: Any? = 0, settings: Any? = mapOf("organization" to "octo-org"), credential: Any? = secret) =
        manage("PUT", "/vendors/$vendorId/connection", mapOf("expectedVersion" to expectedVersion, "settings" to settings, "credential" to credential), token)

    private fun verify(vendorId: String, token: String) = manage("POST", "/vendors/$vendorId/connection/verify", null, token)

    private fun source(response: HttpResponse<String>): JsonNode {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200)
        return json(response).path("seatSource")
    }

    private fun errorOf(response: HttpResponse<String>) = response.statusCode() to json(response).path("error").path("code").asString()

    /** 연결·원장·멱등 기록의 모든 열을 글자로 모은다. */
    private fun storedText(): String = listOf("vendor_connections", "management_commands", "seat_assignments", "seat_sync_runs").joinToString("\n") { table ->
        jdbc.sql("SELECT coalesce(string_agg(t::text, E'\\n'), '') FROM enrollment.$table t").query(String::class.java).single()
    }

    @Test fun `연결을 만들면 권위가 커넥터가 되고 자격증명은 암호문으로만 남는다 — 응답·DB·로그·멱등 기록 어디에도 평문이 없다`(output: CapturedOutput) {
        val token = adminToken()
        val copilot = vendor("copilot", "copilot_business", token)
        // 연결 전: 커넥터가 있는 플랜이라 수동 기록이 임시 권위다.
        with(copilot.path("seatSource")) {
            assertThat(listOf(path("authority").asString(), path("provisional").asBoolean(), path("connector").path("connectorId").asString()))
                .containsExactly("manual", true, "copilot")
            assertThat(path("connector").path("capabilities").toList().map { it.asString() }).containsExactly("seat_list", "seat_release", "seat_restore")
            assertThat(path("connector").path("accountKind").asString()).isEqualTo("github_login")
            assertThat(path("connection").isNull).isTrue()
        }
        val vendorId = copilot.path("vendorId").asString()

        val created = connect(vendorId, token)
        assertThat(created.headers().firstValue("ETag")).hasValue("\"connection-1\"")
        with(source(created)) {
            assertThat(listOf(path("authority").asString(), path("provisional").asBoolean())).containsExactly("connector", false)
            val connection = path("connection")
            assertThat(connection.path("version").asLong()).isEqualTo(1)
            assertThat(connection.path("connectorId").asString()).isEqualTo("copilot")
            assertThat(connection.path("settings").path("organization").asString()).isEqualTo("octo-org")
            assertThat(connection.path("credential").propertyNames().toList()).containsExactlyInAnyOrder("configured", "updatedAt")
            assertThat(connection.path("credential").path("configured").asBoolean()).isTrue()
            assertThat(connection.path("credential").path("updatedAt").asString()).isEqualTo(clock.now.toString())
            assertThat(listOf(connection.at("/check/status").asString(), connection.at("/sync/status").asString())).containsExactly("unverified", "pending")
        }
        secretVariants.forEach { assertThat(created.body()).doesNotContain(it) }
        // PUT 은 멱등 응답 기록을 남기지 않는다 — 요청 본문의 해시도 없다.
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.management_commands WHERE command_path LIKE '%connection%'").query(Int::class.java).single()).isZero()
        val (ciphertext, keyId) = jdbc.sql("SELECT credential_ciphertext, credential_key_id FROM enrollment.vendor_connections WHERE vendor_id = :id")
            .param("id", vendorId).query { rs, _ -> rs.getString(1) to rs.getString(2) }.single()
        assertThat(keyId).isEqualTo("k1")
        assertThat(ciphertext).isNotBlank()

        // 확인: 커넥터는 복호화한 같은 자격증명과 비밀 아닌 설정을 받는다.
        clock.now = clock.now.plusSeconds(30)
        val verified = source(verify(vendorId, token))
        assertThat(copilotConnector.seen.single().let { it.credential.reveal() to it.settings }).isEqualTo(secret to mapOf("organization" to "octo-org"))
        assertThat(listOf(verified.at("/connection/check/status").asString(), verified.at("/connection/check/checkedAt").asString()))
            .containsExactly("verified", clock.now.toString())
        assertThat(verified.at("/connection/version").asLong()).describedAs("확인은 판을 올리지 않는다").isEqualTo(1)

        val outcomes = listOf(ConnectorFailure.Kind.INVALID_CREDENTIALS to "invalid_credentials", ConnectorFailure.Kind.INSUFFICIENT_PERMISSION to "insufficient_permission",
            ConnectorFailure.Kind.UNAVAILABLE to "unavailable", ConnectorFailure.Kind.RATE_LIMITED to "unavailable")
        outcomes.forEach { (kind, expected) ->
            copilotConnector.failure = kind
            assertThat(source(verify(vendorId, token)).at("/connection/check/status").asString()).isEqualTo(expected)
        }

        val stored = storedText()
        val logs = output.all
        secretVariants.forEach {
            assertThat(stored).doesNotContain(it)
            assertThat(logs).doesNotContain(it)
        }
    }

    @Test fun `교체는 판을 올리고 확인 상태를 되돌리며 대상이 바뀌면 동기화 기록을 비운다 · 삭제는 암호문을 지운다`() {
        val token = adminToken()
        val vendorId = vendor("copilot", "copilot_enterprise", token).path("vendorId").asString()
        assertThat(connect(vendorId, token).statusCode()).isEqualTo(200)
        assertThat(source(verify(vendorId, token)).at("/connection/check/status").asString()).isEqualTo("verified")
        val syncedAt = clock.now.minusSeconds(600)
        jdbc.sql("UPDATE enrollment.vendor_connections SET last_sync_succeeded_at = :at WHERE vendor_id = :id")
            .param("at", java.sql.Timestamp.from(syncedAt)).param("id", vendorId).update()

        assertThat(errorOf(connect(vendorId, token, expectedVersion = 0))).isEqualTo(409 to "version_conflict")
        // 자격증명만 바꾸면(같은 커넥터·설정) 동기화 기록은 남는다.
        val rotated = source(connect(vendorId, token, expectedVersion = 1, credential = "fake-vendor-credential-rotated-" + "a".repeat(20)))
        assertThat(listOf(rotated.at("/connection/version").asLong(), rotated.at("/connection/check/status").asString(), rotated.at("/connection/sync/lastSucceededAt").asString()))
            .containsExactly(2L, "unverified", syncedAt.toString())
        // 설정이 바뀌면 다른 대상의 기록이라 비운다.
        val retargeted = source(connect(vendorId, token, expectedVersion = 2, settings = mapOf("organization" to "other-org")))
        assertThat(listOf(retargeted.at("/connection/version").asLong(), retargeted.at("/connection/sync/status").asString())).containsExactly(3L, "pending")
        assertThat(retargeted.at("/connection/sync/lastSucceededAt").isNull).isTrue()

        assertThat(errorOf(manage("DELETE", "/vendors/$vendorId/connection", null, token))).isEqualTo(400 to "invalid_request")
        assertThat(errorOf(manage("DELETE", "/vendors/$vendorId/connection", null, token, etag = "\"connection-1\""))).isEqualTo(409 to "version_conflict")
        assertThat(manage("DELETE", "/vendors/$vendorId/connection", null, token, etag = "\"connection-3\"").statusCode()).isEqualTo(204)
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.vendor_connections WHERE vendor_id = :id AND deleted_at IS NOT NULL AND credential_ciphertext IS NULL AND credential_key_id IS NULL")
            .param("id", vendorId).query(Int::class.java).single()).isEqualTo(1)
        assertThat(errorOf(verify(vendorId, token))).isEqualTo(404 to "not_found")
        // 지운 뒤에는 새 연결을 만든다(판 1부터). 지운 연결은 이력으로 남는다.
        assertThat(source(connect(vendorId, token)).at("/connection/version").asLong()).isEqualTo(1)
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.vendor_connections WHERE vendor_id = :id").param("id", vendorId).query(Int::class.java).single()).isEqualTo(2)
    }

    @Test fun `커넥터는 제품과 계약 플랜으로 고른다 — 커넥터가 없는 플랜·이 배포에 구현이 없는 플랜은 연결할 수 없고 설정은 설명의 키와 같아야 한다`() {
        val token = adminToken()
        val openai = vendor("openai_biz", "business", token).path("vendorId").asString()
        val cursor = vendor("cursor", "cursor_enterprise", token)
        assertThat(errorOf(connect(openai, token, settings = emptyMap<String, String>()))).isEqualTo(422 to "connector_unavailable")
        // Cursor Enterprise 는 설명(커넥터가 있는 플랜)은 있지만 이 배포에 구현이 없다.
        assertThat(cursor.at("/seatSource/connector/capabilities").toList().map { it.asString() }).containsExactly("seat_list", "seat_release", "billing")
        assertThat(errorOf(connect(cursor.path("vendorId").asString(), token, settings = emptyMap<String, String>()))).isEqualTo(422 to "connector_unavailable")

        val copilot = vendor("copilot", "copilot_business", token).path("vendorId").asString()
        listOf(
            mapOf("settings" to emptyMap<String, String>()),
            mapOf("settings" to mapOf("organization" to "octo-org", "extra" to "x")),
            mapOf("settings" to mapOf("organization" to " ")),
            mapOf("settings" to mapOf("organization" to 1)),
            mapOf("credential" to " "),
            mapOf("credential" to null),
            mapOf("expectedVersion" to null),
            mapOf("expectedVersion" to -1),
        ).forEach { change ->
            val body = mutableMapOf<String, Any?>("expectedVersion" to 0, "settings" to mapOf("organization" to "octo-org"), "credential" to secret).apply { putAll(change) }
            val response = manage("PUT", "/vendors/$copilot/connection", body, token)
            assertThat(errorOf(response)).describedAs(change.toString()).isEqualTo(400 to "invalid_request")
            assertThat(json(response).at("/error/fieldErrors/0/field").asString()).isEqualTo(change.keys.single())
        }
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.vendor_connections").query(Int::class.java).single()).isZero()

        // 설정이 없는 커넥터(Claude Enterprise)는 빈 설정이다.
        val claudeVendor = vendor("claude_team", "enterprise", token)
        val claude = claudeVendor.path("vendorId").asString()
        assertThat(source(connect(claude, token, settings = emptyMap<String, String>())).at("/connection/connectorId").asString()).isEqualTo("claude_enterprise")
        // 계약을 Team 플랜으로 정정하면 그 플랜에는 커넥터가 없다 — 교체할 수 없다.
        val team = mapOf("planId" to "team", "effectiveFrom" to "2026-09-09", "tiers" to listOf(mapOf("label" to "표준", "seats" to 3, "monthlyFeePerSeatUsd" to "19")))
        val corrected = manage("PUT", "/vendors/$claude/contract", mapOf("expectedVersion" to claudeVendor.path("version").asLong(), "displayName" to "Claude", "contract" to team), token)
        assertThat(corrected.statusCode()).withFailMessage(corrected.body()).isEqualTo(200)
        assertThat(json(corrected).at("/vendor/seatSource/connector").isNull).isTrue()
        assertThat(errorOf(connect(claude, token, expectedVersion = 1, settings = emptyMap<String, String>()))).describedAs("Team 플랜은 커넥터가 없다").isEqualTo(422 to "connector_unavailable")
    }

    @Test fun `확인 호출 사이 연결이 바뀌면 결과를 쓰지 않고, 행의 키가 설정에 없으면 평문 없이 실패한다`() {
        val token = adminToken()
        val vendorId = vendor("copilot", "copilot_business", token).path("vendorId").asString()
        connect(vendorId, token)
        copilotConnector.during = {
            jdbc.sql("UPDATE enrollment.vendor_connections SET version = version + 1 WHERE vendor_id = :id AND deleted_at IS NULL").param("id", vendorId).update()
        }
        assertThat(errorOf(verify(vendorId, token))).isEqualTo(409 to "version_conflict")
        assertThat(jdbc.sql("SELECT check_status::text FROM enrollment.vendor_connections WHERE vendor_id = :id").param("id", vendorId).query(String::class.java).single())
            .isEqualTo("unverified")

        copilotConnector.during = null
        jdbc.sql("UPDATE enrollment.vendor_connections SET credential_key_id = 'retired' WHERE vendor_id = :id").param("id", vendorId).update()
        val unavailable = verify(vendorId, token)
        assertThat(errorOf(unavailable)).isEqualTo(503 to "credential_key_unavailable")
        secretVariants.forEach { assertThat(unavailable.body()).doesNotContain(it) }
    }

    @Test fun `조직 경계와 권한 — 다른 조직의 경로·다른 조직의 등록 제품은 없는 것이고 토큰이 없으면 401 이다`() {
        val token = adminToken()
        val vendorId = vendor("copilot", "copilot_business", token).path("vendorId").asString()
        val other = data.tenant().id
        val otherAdmin = data.member(other, "admin@other.example.test").id
        jdbc.sql("INSERT INTO enrollment.managed_vendors (tenant_id,vendor_id,kind,source,created_at) VALUES (:t,'other-copilot','copilot','manual',now())").param("t", other).update()
        jdbc.sql("""INSERT INTO enrollment.vendor_contract_versions (tenant_id,vendor_id,version,display_name,contract,recorded_at,recorded_by)
            VALUES (:t,'other-copilot',1,'c',CAST('{"planId":"copilot_business","tiers":[]}' AS jsonb),now(),:a)""").param("t", other).param("a", otherAdmin).update()

        fun request(organization: UUID, path: String, auth: String?): HttpResponse<String> {
            val builder = HttpRequest.newBuilder(URI("http://localhost:$port/api/v1/organizations/$organization$path")).header("Content-Type", "application/json")
            auth?.let { builder.header("Authorization", "Bearer $it") }
            return http.send(builder.PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                mapOf("expectedVersion" to 0, "settings" to mapOf("organization" to "octo-org"), "credential" to secret)))).build(), HttpResponse.BodyHandlers.ofString())
        }
        assertThat(errorOf(request(other, "/vendors/other-copilot/connection", token))).isEqualTo(404 to "not_found")
        assertThat(errorOf(request(tenant, "/vendors/other-copilot/connection", token))).isEqualTo(404 to "not_found")
        assertThat(request(tenant, "/vendors/$vendorId/connection", null).statusCode()).isEqualTo(401)
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.vendor_connections").query(Int::class.java).single()).isZero()
    }

    @Test fun `구성원(member)은 연결을 만들지 못한다`() {
        val token = tokens().path("access_token").asString()
        jdbc.sql("INSERT INTO enrollment.managed_vendors (tenant_id,vendor_id,kind,source,created_at) VALUES (:t,'copilot-1','copilot','manual',now())").param("t", tenant).update()
        assertThat(errorOf(connect("copilot-1", token))).isEqualTo(403 to "forbidden")
    }

    @Test fun `등록 제품을 보관하면 그 제품의 연결과 암호문이 같이 지워진다`() {
        val token = adminToken()
        val vendor = vendor("copilot", "copilot_business", token)
        val vendorId = vendor.path("vendorId").asString()
        connect(vendorId, token)
        val archived = manage("DELETE", "/vendors/$vendorId", null, token, etag = "\"vendor-${vendor.path("version").asLong()}\"")
        assertThat(archived.statusCode()).withFailMessage(archived.body()).isEqualTo(204)
        assertThat(jdbc.sql("SELECT deleted_by::text || ':' || (credential_ciphertext IS NULL)::text FROM enrollment.vendor_connections WHERE vendor_id = :id")
            .param("id", vendorId).query(String::class.java).single()).isEqualTo("$member:true")
        assertThat(errorOf(verify(vendorId, token))).isEqualTo(404 to "not_found")
    }
}
