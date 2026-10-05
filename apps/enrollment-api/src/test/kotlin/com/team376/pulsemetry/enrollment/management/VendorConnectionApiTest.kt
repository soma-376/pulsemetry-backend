package com.team376.pulsemetry.enrollment.management

import com.team376.pulsemetry.connector.vendor.MockVendorServer
import com.team376.pulsemetry.connector.vendor.reply
import com.team376.pulsemetry.enrollment.auth.AbstractUserAuthApiTest
import com.team376.pulsemetry.enrollment.auth.AuthClockConfig
import com.team376.pulsemetry.enrollment.support.EnrollmentTestData
import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.context.annotation.Import
import tools.jackson.databind.JsonNode
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.util.UUID

/**
 * 벤더 연결 명령 (ADR 0048 §6). 기대값은 ADR 의 문장에서 쓴다 — 커넥터는 제품·계약 플랜으로 고르고 이 배포에 구현이 없으면 만들 수 없다,
 * 자격증명은 암호문으로만 남고 응답·로그·멱등 기록에 없다, 교체는 확인 상태를 되돌리고 대상이 바뀌면 동기화 기록을 비운다, 삭제·보관은 암호문을 지운다,
 * 확인은 트랜잭션 밖에서 부르고 그 사이 연결이 바뀌면 결과를 쓰지 않는다.
 *
 * 커넥터는 실제 구현이고 벤더 API 는 모의 서버(JDK 내장 HTTP 서버)다 — 실제 벤더를 부르지 않는다. 확인 결과는 모의 서버의 응답으로 정한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["pulsemetry.user-auth.enabled=true", "pulsemetry.management.enabled=true",
        "pulsemetry.management.onboarding-otlp-endpoint=https://ingest.example.test",
        "pulsemetry.management.response-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "pulsemetry.user-auth.allowed-origins=http://localhost:3000",
        "pulsemetry.vendor-connections.enabled=true",
        "pulsemetry.vendor-connections.credential-keys.k1=BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=",
        "pulsemetry.vendor-connections.credential-key-id=k1",
        "pulsemetry.vendor-connections.sync.interval=PT1H", "pulsemetry.vendor-connections.sync.check-interval=PT1H", "pulsemetry.vendor-connections.sync.lease=PT5M",
        "pulsemetry.vendor-connections.http.request-timeout=PT5S", "pulsemetry.vendor-connections.http.max-attempts=2",
        "pulsemetry.vendor-connections.http.retry-backoff=PT0S", "pulsemetry.vendor-connections.http.max-retry-wait=PT0S"])
@AutoConfigureMockMvc
@Import(PostgresContainerConfig::class, EnrollmentTestData::class, AuthClockConfig::class)
@ExtendWith(OutputCaptureExtension::class)
class VendorConnectionApiTest : AbstractUserAuthApiTest() {

    companion object {
        /** Cursor·Claude 의 벤더 API 를 흉내 낸다. 클래스 하나가 같은 서버를 쓴다. */
        val vendors = MockVendorServer()

        @JvmStatic @DynamicPropertySource fun vendorApis(registry: DynamicPropertyRegistry) {
            listOf("claude_enterprise", "cursor_enterprise").forEach { id ->
                registry.add("pulsemetry.vendor-connections.base-urls.$id") { vendors.base.toString() }
            }
        }
    }

    private val cursorMembers = "/teams/members"

    @BeforeEach fun vendorReplies() {
        vendors.received.clear()
        vendors.on("GET", cursorMembers, reply(200, """{"teamMembers":[]}"""))
        vendors.on("GET", "/api/v1/organizations/users", reply(200, """{"data":[],"has_more":false,"first_id":null,"last_id":null}"""))
    }

    private val secret = "fake-vendor-credential-" + "Zq9".repeat(12)
    private val secretVariants get() = listOf(secret, Base64.getEncoder().encodeToString(secret.toByteArray()))

    private fun json(response: HttpResponse<String>): JsonNode = mapper.readTree(response.body())

    private fun vendor(kind: String, plan: String, token: String): JsonNode {
        val contract = mapOf("planId" to plan, "effectiveFrom" to "2026-09-09", "tiers" to listOf(mapOf("label" to "표준", "seats" to 3, "monthlyFeePerSeatUsd" to "19")))
        val created = manage("POST", "/vendors", mapOf("kind" to kind, "displayName" to kind, "contract" to contract), token)
        assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(201)
        return json(created).path("vendor")
    }

    private fun connect(vendorId: String, token: String, expectedVersion: Any? = 0, settings: Any? = emptyMap<String, String>(), credential: Any? = secret) =
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
        val cursor = vendor("cursor", "cursor_enterprise", token)
        // 연결 전: 커넥터가 있는 플랜이라 수동 기록이 임시 권위다.
        with(cursor.path("seatSource")) {
            assertThat(listOf(path("authority").asString(), path("provisional").asBoolean(), path("connector").path("connectorId").asString()))
                .containsExactly("manual", true, "cursor_enterprise")
            // 구현한 기능은 ADR 0049·0050 의 표(Cursor 는 목록·해제·청구)이고, 벤더가 지원하는 기능은 문서의 결론이다.
            assertThat(path("connector").path("capabilities").toList().map { it.asString() }).containsExactly("seat_list", "seat_release", "billing")
            assertThat(path("connector").path("supported").toList().map { it.asString() }).containsExactly("seat_list", "seat_release", "billing")
            assertThat(path("connector").path("accountKind").asString()).isEqualTo("email")
            assertThat(path("connection").isNull).isTrue()
        }
        val vendorId = cursor.path("vendorId").asString()

        val created = connect(vendorId, token)
        assertThat(created.headers().firstValue("ETag")).hasValue("\"connection-1\"")
        with(source(created)) {
            assertThat(listOf(path("authority").asString(), path("provisional").asBoolean())).containsExactly("connector", false)
            val connection = path("connection")
            assertThat(connection.path("version").asLong()).isEqualTo(1)
            assertThat(connection.path("connectorId").asString()).isEqualTo("cursor_enterprise")
            assertThat(connection.path("settings").let { it.isObject && it.isEmpty }).describedAs("Cursor 는 비밀 아닌 설정이 없다").isTrue()
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

        // 확인: 커넥터는 복호화한 같은 자격증명을 문서의 헤더(Basic, 키가 사용자 이름)로 보낸다.
        clock.now = clock.now.plusSeconds(30)
        val verified = source(verify(vendorId, token))
        assertThat(vendors.requests(cursorMembers).single().header("Authorization")).isEqualTo("Basic " + Base64.getEncoder().encodeToString("$secret:".toByteArray()))
        assertThat(listOf(verified.at("/connection/check/status").asString(), verified.at("/connection/check/checkedAt").asString()))
            .containsExactly("verified", clock.now.toString())
        assertThat(verified.at("/connection/version").asLong()).describedAs("확인은 판을 올리지 않는다").isEqualTo(1)

        val outcomes = listOf(reply(401, """{"error":"unauthorized"}""") to "invalid_credentials",
            reply(403, """{"error":"forbidden"}""") to "insufficient_permission",
            reply(503, "{}") to "unavailable", reply(429, "{}", "Retry-After" to "60") to "unavailable")
        outcomes.forEach { (answer, expected) ->
            vendors.on("GET", cursorMembers, answer)
            assertThat(source(verify(vendorId, token)).at("/connection/check/status").asString()).isEqualTo(expected)
        }

        val stored = storedText()
        val logs = output.all
        secretVariants.forEach {
            assertThat(stored).doesNotContain(it)
            assertThat(logs).doesNotContain(it)
        }
    }

    @Test fun `교체는 판을 올리고 확인 상태를 되돌리며 같은 대상의 동기화 기록은 남긴다 · 삭제는 암호문을 지운다`() {
        val token = adminToken()
        val vendorId = vendor("cursor", "cursor_enterprise", token).path("vendorId").asString()
        assertThat(connect(vendorId, token).statusCode()).isEqualTo(200)
        assertThat(source(verify(vendorId, token)).at("/connection/check/status").asString()).isEqualTo("verified")
        val syncedAt = clock.now.minusSeconds(600)
        jdbc.sql("UPDATE enrollment.vendor_connections SET last_sync_succeeded_at = :at WHERE vendor_id = :id")
            .param("at", java.sql.Timestamp.from(syncedAt)).param("id", vendorId).update()

        assertThat(errorOf(connect(vendorId, token, expectedVersion = 0))).isEqualTo(409 to "version_conflict")
        // 자격증명만 바꾸면(같은 커넥터·설정) 동기화 기록은 남는다. 설정이 있는 커넥터가 없어(ADR 0054) 대상이 바뀌는 교체는 없다.
        val rotated = source(connect(vendorId, token, expectedVersion = 1, credential = "fake-vendor-credential-rotated-" + "a".repeat(20)))
        assertThat(listOf(rotated.at("/connection/version").asLong(), rotated.at("/connection/check/status").asString(), rotated.at("/connection/sync/lastSucceededAt").asString()))
            .containsExactly(2L, "unverified", syncedAt.toString())

        assertThat(errorOf(manage("DELETE", "/vendors/$vendorId/connection", null, token))).isEqualTo(400 to "invalid_request")
        assertThat(errorOf(manage("DELETE", "/vendors/$vendorId/connection", null, token, etag = "\"connection-1\""))).isEqualTo(409 to "version_conflict")
        assertThat(manage("DELETE", "/vendors/$vendorId/connection", null, token, etag = "\"connection-2\"").statusCode()).isEqualTo(204)
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.vendor_connections WHERE vendor_id = :id AND deleted_at IS NOT NULL AND credential_ciphertext IS NULL AND credential_key_id IS NULL")
            .param("id", vendorId).query(Int::class.java).single()).isEqualTo(1)
        assertThat(errorOf(verify(vendorId, token))).isEqualTo(404 to "not_found")
        // 지운 뒤에는 새 연결을 만든다(판 1부터). 지운 연결은 이력으로 남는다.
        assertThat(source(connect(vendorId, token)).at("/connection/version").asLong()).isEqualTo(1)
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.vendor_connections WHERE vendor_id = :id").param("id", vendorId).query(Int::class.java).single()).isEqualTo(2)
    }

    @Test fun `커넥터는 제품과 계약 플랜으로 고른다 — 커넥터가 없는 플랜은 연결할 수 없고 설정은 설명의 키와 같아야 한다`() {
        val token = adminToken()
        val openai = vendor("openai_biz", "business", token).path("vendorId").asString()
        val cursor = vendor("cursor", "cursor_enterprise", token)
        assertThat(errorOf(connect(openai, token, settings = emptyMap<String, String>()))).isEqualTo(422 to "connector_unavailable")
        assertThat(cursor.at("/seatSource/connector/supported").toList().map { it.asString() }).containsExactly("seat_list", "seat_release", "billing")
        assertThat(source(connect(cursor.path("vendorId").asString(), token, settings = emptyMap<String, String>())).at("/connection/connectorId").asString())
            .isEqualTo("cursor_enterprise")

        // 연동 대상이 아닌 제품(ADR 0054)은 카탈로그 플랜이 있어도 커넥터가 없는 플랜이다 — 연결을 거부한다.
        for ((kind, plan) in listOf("copilot" to "copilot_business", "gemini" to "gemini_enterprise")) {
            val unsupported = vendor(kind, plan, token)
            assertThat(unsupported.at("/seatSource/connector").isNull).describedAs(kind).isTrue()
            assertThat(errorOf(connect(unsupported.path("vendorId").asString(), token))).describedAs(kind).isEqualTo(422 to "connector_unavailable")
            assertThat(errorOf(connect(unsupported.path("vendorId").asString(), token, settings = mapOf("organization" to "octo-org")))).describedAs(kind)
                .isEqualTo(422 to "connector_unavailable")
        }

        // 설정이 없는 커넥터(Claude Enterprise)는 빈 설정이어야 한다. 설명에 없는 키·형식이 틀린 입력은 연결을 만들지 않는다.
        val claudeVendor = vendor("claude_team", "enterprise", token)
        val claude = claudeVendor.path("vendorId").asString()
        listOf(
            mapOf("settings" to mapOf("organization" to "octo-org")),
            mapOf("settings" to mapOf("extra" to 1)),
            mapOf("credential" to " "),
            mapOf("credential" to null),
            mapOf("expectedVersion" to null),
            mapOf("expectedVersion" to -1),
        ).forEach { change ->
            val body = mutableMapOf<String, Any?>("expectedVersion" to 0, "settings" to emptyMap<String, String>(), "credential" to secret).apply { putAll(change) }
            val response = manage("PUT", "/vendors/$claude/connection", body, token)
            assertThat(errorOf(response)).describedAs(change.toString()).isEqualTo(400 to "invalid_request")
            assertThat(json(response).at("/error/fieldErrors/0/field").asString()).isEqualTo(change.keys.single())
        }
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.vendor_connections WHERE vendor_id = :id").param("id", claude).query(Int::class.java).single()).isZero()
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
        val vendorId = vendor("cursor", "cursor_enterprise", token).path("vendorId").asString()
        connect(vendorId, token)
        vendors.on("GET", cursorMembers, {
            jdbc.sql("UPDATE enrollment.vendor_connections SET version = version + 1 WHERE vendor_id = :id AND deleted_at IS NULL").param("id", vendorId).update()
            MockVendorServer.Reply(200, """{"teamMembers":[]}""")
        })
        assertThat(errorOf(verify(vendorId, token))).isEqualTo(409 to "version_conflict")
        assertThat(jdbc.sql("SELECT check_status::text FROM enrollment.vendor_connections WHERE vendor_id = :id").param("id", vendorId).query(String::class.java).single())
            .isEqualTo("unverified")

        jdbc.sql("UPDATE enrollment.vendor_connections SET credential_key_id = 'retired' WHERE vendor_id = :id").param("id", vendorId).update()
        val unavailable = verify(vendorId, token)
        assertThat(errorOf(unavailable)).isEqualTo(503 to "credential_key_unavailable")
        secretVariants.forEach { assertThat(unavailable.body()).doesNotContain(it) }
    }

    @Test fun `조직 경계와 권한 — 다른 조직의 경로·다른 조직의 등록 제품은 없는 것이고 토큰이 없으면 401 이다`() {
        val token = adminToken()
        val vendorId = vendor("cursor", "cursor_enterprise", token).path("vendorId").asString()
        val other = data.tenant().id
        val otherAdmin = data.member(other, "admin@other.example.test").id
        jdbc.sql("INSERT INTO enrollment.managed_vendors (tenant_id,vendor_id,kind,source,created_at) VALUES (:t,'other-cursor','cursor','manual',now())").param("t", other).update()
        jdbc.sql("""INSERT INTO enrollment.vendor_contract_versions (tenant_id,vendor_id,version,display_name,contract,recorded_at,recorded_by)
            VALUES (:t,'other-cursor',1,'c',CAST('{"planId":"cursor_enterprise","tiers":[]}' AS jsonb),now(),:a)""").param("t", other).param("a", otherAdmin).update()

        fun request(organization: UUID, path: String, auth: String?): HttpResponse<String> {
            val builder = HttpRequest.newBuilder(URI("http://localhost:$port/api/v1/organizations/$organization$path")).header("Content-Type", "application/json")
            auth?.let { builder.header("Authorization", "Bearer $it") }
            return http.send(builder.PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                mapOf("expectedVersion" to 0, "settings" to emptyMap<String, String>(), "credential" to secret)))).build(), HttpResponse.BodyHandlers.ofString())
        }
        assertThat(errorOf(request(other, "/vendors/other-cursor/connection", token))).isEqualTo(404 to "not_found")
        assertThat(errorOf(request(tenant, "/vendors/other-cursor/connection", token))).isEqualTo(404 to "not_found")
        assertThat(request(tenant, "/vendors/$vendorId/connection", null).statusCode()).isEqualTo(401)
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.vendor_connections").query(Int::class.java).single()).isZero()
    }

    @Test fun `구성원(member)은 연결을 만들지 못한다`() {
        val token = tokens().path("access_token").asString()
        jdbc.sql("INSERT INTO enrollment.managed_vendors (tenant_id,vendor_id,kind,source,created_at) VALUES (:t,'cursor-1','cursor','manual',now())").param("t", tenant).update()
        assertThat(errorOf(connect("cursor-1", token))).isEqualTo(403 to "forbidden")
    }

    @Test fun `등록 제품을 보관하면 그 제품의 연결과 암호문이 같이 지워진다`() {
        val token = adminToken()
        val vendor = vendor("cursor", "cursor_enterprise", token)
        val vendorId = vendor.path("vendorId").asString()
        connect(vendorId, token)
        val archived = manage("DELETE", "/vendors/$vendorId", null, token, etag = "\"vendor-${vendor.path("version").asLong()}\"")
        assertThat(archived.statusCode()).withFailMessage(archived.body()).isEqualTo(204)
        assertThat(jdbc.sql("SELECT deleted_by::text || ':' || (credential_ciphertext IS NULL)::text FROM enrollment.vendor_connections WHERE vendor_id = :id")
            .param("id", vendorId).query(String::class.java).single()).isEqualTo("$member:true")
        assertThat(errorOf(verify(vendorId, token))).isEqualTo(404 to "not_found")
    }
}
