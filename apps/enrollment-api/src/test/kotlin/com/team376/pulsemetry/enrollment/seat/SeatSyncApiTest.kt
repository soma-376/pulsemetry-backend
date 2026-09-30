package com.team376.pulsemetry.enrollment.seat

import com.team376.pulsemetry.connector.vendor.MockVendorServer
import com.team376.pulsemetry.connector.vendor.reply
import com.team376.pulsemetry.enrollment.auth.AbstractUserAuthApiTest
import com.team376.pulsemetry.enrollment.auth.AuthClockConfig
import com.team376.pulsemetry.enrollment.support.EnrollmentTestData
import com.team376.pulsemetry.persistence.enrollment.operation.OperationStatus
import com.team376.pulsemetry.persistence.enrollment.operation.OperationStore
import com.team376.pulsemetry.persistence.enrollment.seat.MemberLink
import com.team376.pulsemetry.persistence.enrollment.seat.SeatLedger
import com.team376.pulsemetry.persistence.enrollment.seat.SeatState
import com.team376.pulsemetry.persistence.enrollment.seat.VendorConnectionStore
import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import tools.jackson.databind.JsonNode
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 좌석 동기화 — 연결(API) → 주기 실행 → 벤더 API(모의 서버) → 원장 → 연결의 동기화 상태·요청 작업까지 (ADR 0048 §3·§7).
 * 모의 응답은 벤더 문서의 예시를 재현한다. 기대값은 ADR 의 표에서 쓴다 — 목록 전체로 맞추고, 실패는 원장을 바꾸지 않으며, 한 연결의 실패가 다른 연결을 막지 않는다.
 * 주기 실행은 설정의 확인 주기(1시간)로 돌지 않게 두고 테스트가 한 바퀴씩 부른다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["pulsemetry.user-auth.enabled=true", "pulsemetry.management.enabled=true",
        "pulsemetry.management.onboarding-otlp-endpoint=https://ingest.example.test",
        "pulsemetry.management.response-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "pulsemetry.user-auth.allowed-origins=http://localhost:3000",
        "pulsemetry.vendor-connections.enabled=true",
        "pulsemetry.vendor-connections.credential-keys.k1=BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=",
        "pulsemetry.vendor-connections.credential-key-id=k1",
        "pulsemetry.vendor-connections.sync.interval=PT6H", "pulsemetry.vendor-connections.sync.check-interval=PT1H", "pulsemetry.vendor-connections.sync.lease=PT5M",
        "pulsemetry.vendor-connections.http.request-timeout=PT5S", "pulsemetry.vendor-connections.http.max-attempts=2",
        "pulsemetry.vendor-connections.http.retry-backoff=PT0S", "pulsemetry.vendor-connections.http.max-retry-wait=PT0S"])
@AutoConfigureMockMvc
@Import(PostgresContainerConfig::class, EnrollmentTestData::class, AuthClockConfig::class)
class SeatSyncApiTest : AbstractUserAuthApiTest() {

    companion object {
        val vendors = MockVendorServer()

        @JvmStatic @DynamicPropertySource fun vendorApis(registry: DynamicPropertyRegistry) {
            listOf("copilot", "claude_enterprise", "cursor_enterprise", "gemini").forEach { id ->
                registry.add("pulsemetry.vendor-connections.base-urls.$id") { vendors.base.toString() }
            }
        }
    }

    @Autowired private lateinit var synchronizer: SeatSynchronizer
    @Autowired private lateinit var ledger: SeatLedger
    @Autowired private lateinit var connections: VendorConnectionStore
    @Autowired private lateinit var manager: PlatformTransactionManager

    private val copilotSeats = "/orgs/octo-org/copilot/billing/seats"
    private val secret = "fake-vendor-credential-" + "s".repeat(24)

    /** Copilot 두 페이지(문서 예시: octocat, 해제 예정 hubot), Claude 구성원 한 명과 대기 중 초대 하나. */
    @BeforeEach fun vendorReplies() {
        vendors.received.clear()
        vendors.on("GET", copilotSeats,
            reply(200, """{"total_seats":2,"seats":[{"assignee":{"login":"octocat","id":1,"type":"User"},"pending_cancellation_date":null,
                "last_activity_at":"2026-09-01T10:00:00Z","created_at":"2024-10-01T19:32:20Z","plan_type":"business"}]}""",
                "link" to "<${vendors.base}$copilotSeats?per_page=100&page=2>; rel=\"next\""),
            reply(200, """{"total_seats":2,"seats":[{"assignee":{"login":"hubot","id":2,"type":"User"},"pending_cancellation_date":"2026-09-30",
                "last_activity_at":null,"created_at":"2024-11-01T00:00:00Z","plan_type":"business"}]}"""))
        vendors.on("GET", "/v1/organizations/users", reply(200, """{"data":[{"type":"user","id":"user_01","email":"jane@example.test","name":"Jane","role":"user",
            "added_at":"2026-06-12T09:14:03Z"}],"has_more":false,"first_id":"user_01","last_id":"user_01"}"""))
        vendors.on("GET", "/v1/organizations/invites", reply(200, """{"data":[{"type":"invite","id":"invite_01","email":"newhire@example.test","role":"user",
            "invited_at":"2026-07-06T16:20:11Z","expires_at":"2026-07-27T16:20:11Z","accepted_at":null,"status":"pending"}],"has_more":false,"first_id":"invite_01","last_id":"invite_01"}"""))
    }

    private fun json(response: HttpResponse<String>): JsonNode = mapper.readTree(response.body())

    private fun vendor(kind: String, plan: String, token: String): JsonNode {
        val contract = mapOf("planId" to plan, "effectiveFrom" to "2026-09-09", "tiers" to listOf(mapOf("label" to "표준", "seats" to 5, "monthlyFeePerSeatUsd" to "19")))
        val created = manage("POST", "/vendors", mapOf("kind" to kind, "displayName" to kind, "contract" to contract), token)
        assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(201)
        return json(created).path("vendor")
    }

    private fun connect(vendorId: String, token: String, settings: Map<String, String>) {
        val saved = manage("PUT", "/vendors/$vendorId/connection", mapOf("expectedVersion" to 0, "settings" to settings, "credential" to secret), token)
        assertThat(saved.statusCode()).withFailMessage(saved.body()).isEqualTo(200)
    }

    private data class Setup(val token: String, val copilot: String, val claude: String)

    private fun connectTwo(): Setup {
        val token = adminToken()
        data.member(tenant, "jane@example.test")
        val copilot = vendor("copilot", "copilot_business", token).path("vendorId").asString()
        val claude = vendor("claude_team", "enterprise", token).path("vendorId").asString()
        connect(copilot, token, mapOf("organization" to "octo-org"))
        connect(claude, token, emptyMap())
        return Setup(token, copilot, claude)
    }

    private fun seats(vendorId: String) = ledger.seats(tenant, vendorId).associateBy { it.account }
    private fun runs() = jdbc.sql("SELECT count(*) FROM enrollment.seat_sync_runs").query(Int::class.java).single()
    private fun operation(id: String) = OperationStore(jdbc, manager, clock).find(tenant, UUID.fromString(id))!!

    @Test fun `한 바퀴가 연결마다 벤더 목록 전체를 원장에 옮기고 연결의 동기화 상태를 남긴다`() {
        val setup = connectTwo()
        assertThat(synchronizer.runOnce()).isEqualTo(SeatSynchronizer.Round(applied = 2, failed = 0))

        with(seats(setup.copilot)) {
            assertThat(keys).containsExactlyInAnyOrder("octocat", "hubot")
            assertThat(getValue("octocat").let { Triple(it.state, it.memberId, it.vendorLastActivityAt) }).isEqualTo(Triple(SeatState.ASSIGNED, null, Instant.parse("2026-09-01T10:00:00Z")))
            assertThat(getValue("hubot").let { it.state to it.releaseEffectiveOn }).isEqualTo(SeatState.PENDING_RELEASE to LocalDate.parse("2026-09-30"))
        }
        with(seats(setup.claude)) {
            // 이메일이 정확히 한 구성원과 같으면 잇는다. 대기 중 초대는 배정 대기다.
            assertThat(getValue("jane@example.test").let { Triple(it.state, it.memberLink, it.vendorAccountRef) }).isEqualTo(Triple(SeatState.ASSIGNED, MemberLink.EMAIL_MATCH, "user_01"))
            assertThat(getValue("newhire@example.test").let { it.state to it.memberId }).isEqualTo(SeatState.PENDING_ASSIGNMENT to null)
        }
        listOf(setup.copilot, setup.claude).forEach { vendorId ->
            val sync = connections.view(tenant, vendorId).connection!!.sync
            assertThat(listOf(sync.status, sync.lastSucceededAt)).containsExactly("succeeded", clock.now.toString())
        }
        assertThat(jdbc.sql("SELECT string_agg(status || ':' || listed_seats || ':' || trigger, ',' ORDER BY listed_seats) FROM enrollment.seat_sync_runs").query(String::class.java).single())
            .isEqualTo("succeeded:2:schedule,succeeded:2:schedule")
        assertThat(vendors.requests(copilotSeats).map { it.header("Authorization") }).containsOnly("Bearer $secret")
    }

    @Test fun `한 연결의 실패가 다른 연결을 막지 않고, 실패한 연결의 원장은 그대로 낡은 값으로 남는다`() {
        val setup = connectTwo()
        synchronizer.runOnce()
        val before = seats(setup.copilot)

        // Copilot 이 계속 503 — 정한 횟수만큼 시도하고 실패로 남긴다. Claude 는 계속 동기화된다.
        vendors.on("GET", copilotSeats, reply(503, "{}"))
        vendors.on("GET", "/v1/organizations/users", reply(200, """{"data":[],"has_more":false,"first_id":null,"last_id":null}"""))
        clock.now = clock.now.plus(Duration.ofHours(7))
        val attempts = vendors.requests(copilotSeats).size
        assertThat(synchronizer.runOnce()).isEqualTo(SeatSynchronizer.Round(applied = 1, failed = 1))
        assertThat(vendors.requests(copilotSeats).size - attempts).isEqualTo(2)

        assertThat(seats(setup.copilot)).isEqualTo(before)
        val failing = connections.view(tenant, setup.copilot).connection!!.sync
        assertThat(listOf(failing.status, failing.lastError, failing.lastFailedAt)).containsExactly("failing", "vendor_unavailable", clock.now.toString())
        // Claude 는 목록에서 사라진 구성원을 해제했다(초대는 그대로 대기).
        assertThat(seats(setup.claude).getValue("jane@example.test").state).isEqualTo(SeatState.RELEASED)
    }

    @Test fun `주기 — 마지막 시도가 간격보다 오래된 연결만 다시 돈다`() {
        connectTwo()
        synchronizer.runOnce()
        val first = runs()
        clock.now = clock.now.plus(Duration.ofHours(5))
        assertThat(synchronizer.runOnce()).isEqualTo(SeatSynchronizer.Round(0, 0))
        assertThat(runs()).isEqualTo(first)
        clock.now = clock.now.plus(Duration.ofHours(1)).plusSeconds(1)
        assertThat(synchronizer.runOnce()).isEqualTo(SeatSynchronizer.Round(2, 0))
        assertThat(runs()).isEqualTo(first + 2)
    }

    @Test fun `지금 동기화 — 202 로 작업을 접수하고 다음 한 바퀴가 주기와 무관하게 실행해 결과를 작업에 남긴다`() {
        val setup = connectTwo()
        synchronizer.runOnce()
        val key = UUID.randomUUID().toString()
        val accepted = manage("POST", "/vendors/${setup.copilot}/connection/sync", null, setup.token, key = key)
        assertThat(accepted.statusCode()).withFailMessage(accepted.body()).isEqualTo(202)
        val body = json(accepted)
        val operationId = body.path("operationId").asString()
        assertThat(listOf(body.path("kind").asString(), body.path("status").asString())).containsExactly("seat_sync", "pending")
        assertThat(accepted.headers().firstValue("Location")).hasValue("/api/v1/organizations/$tenant/operations/$operationId")
        assertThat(json(manage("POST", "/vendors/${setup.copilot}/connection/sync", null, setup.token, key = key)).path("operationId").asString()).isEqualTo(operationId)
        assertThat(json(manage("POST", "/vendors/${setup.copilot}/connection/sync", null, setup.token)).path("operationId").asString())
            .describedAs("끝나지 않은 요청이 있으면 새 키도 같은 작업").isEqualTo(operationId)

        assertThat(synchronizer.runOnce()).describedAs("요청이 걸린 Copilot 만 — Claude 는 아직 차례가 아니다").isEqualTo(SeatSynchronizer.Round(1, 0))
        assertThat(operation(operationId).let { it.status to it.targets.single().status.wire }).isEqualTo(OperationStatus.SUCCEEDED to "succeeded")

        // 실패한 요청은 사유를 남긴다.
        vendors.on("GET", copilotSeats, reply(401, """{"message":"Bad credentials"}"""))
        val failedId = json(manage("POST", "/vendors/${setup.copilot}/connection/sync", null, setup.token)).path("operationId").asString()
        synchronizer.runOnce()
        assertThat(operation(failedId).let { it.status to it.targets.single().reason }).isEqualTo(OperationStatus.FAILED to "invalid_credentials")
    }

    @Test fun `연결이 없는 제품의 동기화 요청은 404, 계약을 비우면 커넥터가 맞지 않아 plan_mismatch 로 실패한다`() {
        val setup = connectTwo()
        val openai = vendor("openai_biz", "business", setup.token).path("vendorId").asString()
        assertThat(manage("POST", "/vendors/$openai/connection/sync", null, setup.token).statusCode()).isEqualTo(404)

        assertThat(manage("DELETE", "/vendors/${setup.copilot}/contract", null, setup.token, etag = "\"vendor-${currentVersion(setup.copilot)}\"").statusCode()).isEqualTo(204)
        synchronizer.runOnce()
        assertThat(connections.view(tenant, setup.copilot).connection!!.sync.lastError).isEqualTo("plan_mismatch")
        assertThat(seats(setup.copilot)).isEmpty()
        assertThat(vendors.requests(copilotSeats)).describedAs("플랜이 맞지 않으면 벤더를 부르지 않는다").isEmpty()
    }

    private fun currentVersion(vendorId: String): Long = jdbc.sql("SELECT max(version) FROM enrollment.vendor_contract_versions WHERE vendor_id = :id")
        .param("id", vendorId).query(Long::class.java).single()
}
