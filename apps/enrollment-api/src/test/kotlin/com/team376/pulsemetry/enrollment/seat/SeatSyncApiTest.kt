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
            listOf("claude_enterprise", "cursor_enterprise").forEach { id ->
                registry.add("pulsemetry.vendor-connections.base-urls.$id") { vendors.base.toString() }
            }
        }
    }

    @Autowired private lateinit var synchronizer: SeatSynchronizer
    @Autowired private lateinit var ledger: SeatLedger
    @Autowired private lateinit var connections: VendorConnectionStore
    @Autowired private lateinit var manager: PlatformTransactionManager

    private val cursorMembers = "/teams/members"
    private val cursorSpend = "/teams/spend"
    private val secret = "fake-vendor-credential-" + "s".repeat(24)
    private val basic = "Basic " + java.util.Base64.getEncoder().encodeToString("$secret:".toByteArray())

    /** Cursor 구성원 둘(하나는 제거됨 — 좌석이 아니다)과 이번 주기 지출, Claude 구성원 한 명과 대기 중 초대 하나. 문서 예시를 재현한다. */
    @BeforeEach fun vendorReplies() {
        vendors.received.clear()
        vendors.on("GET", cursorMembers, reply(200, """{"teamMembers":[{"id":"user_c1","email":"Dev@Example.test","name":"Dev","role":"member","isRemoved":false},
            {"id":"user_c2","email":"gone@example.test","name":"Gone","role":"member","isRemoved":true}]}"""))
        // Cursor Enterprise 지출(ADR 0050) — 이번 주기(2026-09-01T00:00Z 시작) on-demand 1234.5 센트.
        vendors.on("POST", cursorSpend, reply(200, """{"teamMemberSpend":[{"userId":"user_c1","spendCents":1234.5,"email":"dev@example.test"}],
            "subscriptionCycleStart":1788220800000,"totalPages":1}"""))
        vendors.on("GET", "/v1/organizations/users", reply(200, """{"data":[{"type":"user","id":"user_01","email":"jane@example.test","name":"Jane","role":"user",
            "added_at":"2026-06-12T09:14:03Z"}],"has_more":false,"first_id":"user_01","last_id":"user_01"}"""))
        vendors.on("GET", "/v1/organizations/invites", reply(200, """{"data":[{"type":"invite","id":"invite_01","email":"newhire@example.test","role":"user",
            "invited_at":"2026-07-06T16:20:11Z","expires_at":"2026-07-27T16:20:11Z","accepted_at":null,"status":"pending"}],"has_more":false,"first_id":"invite_01","last_id":"invite_01"}"""))
        // Claude Enterprise 비용 보고서(ADR 0050) — 이번 달 사용 비용 41280 센트.
        vendors.on("GET", costReport, reply(200, """{"data":[{"starting_at":"2026-08-31T15:00:00Z","ending_at":"2026-08-31T16:00:00Z",
            "results":[{"amount":"41280.000000","currency":"USD","cost_type":"tokens","product":"claude_code"}]}],"has_more":false,"next_page":null}"""))
    }

    private val costReport = "/v1/organizations/analytics/cost_report"
    private fun billing(vendorId: String) = jdbc.sql("""SELECT period_start, period_end, amount_usd, kind, finalized, source, fetched_at FROM enrollment.vendor_billing_periods
            WHERE vendor_id = :v ORDER BY period_start""").param("v", vendorId)
        .query { rs, _ -> listOf(rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant(), rs.getBigDecimal(3).stripTrailingZeros().toPlainString(), rs.getString(4),
            rs.getBoolean(5), rs.getString(6), rs.getTimestamp(7).toInstant()) }.list()

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

    private data class Setup(val token: String, val cursor: String, val claude: String)

    private fun connectTwo(): Setup {
        val token = adminToken()
        data.member(tenant, "jane@example.test")
        val cursor = vendor("cursor", "cursor_enterprise", token).path("vendorId").asString()
        val claude = vendor("claude_team", "enterprise", token).path("vendorId").asString()
        connect(cursor, token, emptyMap())
        connect(claude, token, emptyMap())
        return Setup(token, cursor, claude)
    }

    private fun seats(vendorId: String) = ledger.seats(tenant, vendorId).associateBy { it.account }
    private fun runs() = jdbc.sql("SELECT count(*) FROM enrollment.seat_sync_runs").query(Int::class.java).single()
    private fun operation(id: String) = OperationStore(jdbc, manager, clock).find(tenant, UUID.fromString(id))!!

    @Test fun `한 바퀴가 연결마다 벤더 목록 전체를 원장에 옮기고 연결의 동기화 상태를 남긴다`() {
        val setup = connectTwo()
        assertThat(synchronizer.runOnce()).isEqualTo(SeatSynchronizer.Round(applied = 2, failed = 0))

        with(seats(setup.cursor)) {
            // 제거된 구성원은 좌석이 아니다. 계정 키는 소문자 이메일, 벤더 내부 ID 는 따로 남는다. 활동 시각은 벤더가 주지 않는다(모름).
            assertThat(keys).containsExactly("dev@example.test")
            assertThat(getValue("dev@example.test").let { listOf(it.state, it.memberId, it.vendorAccountRef, it.vendorLastActivityAt) })
                .containsExactly(SeatState.ASSIGNED, null, "user_c1", null)
        }
        with(seats(setup.claude)) {
            // 이메일이 정확히 한 구성원과 같으면 잇는다. 대기 중 초대는 배정 대기다.
            assertThat(getValue("jane@example.test").let { Triple(it.state, it.memberLink, it.vendorAccountRef) }).isEqualTo(Triple(SeatState.ASSIGNED, MemberLink.EMAIL_MATCH, "user_01"))
            assertThat(getValue("newhire@example.test").let { it.state to it.memberId }).isEqualTo(SeatState.PENDING_ASSIGNMENT to null)
        }
        listOf(setup.cursor, setup.claude).forEach { vendorId ->
            val sync = connections.view(tenant, vendorId).connection!!.sync
            assertThat(listOf(sync.status, sync.lastSucceededAt)).containsExactly("succeeded", clock.now.toString())
        }
        assertThat(jdbc.sql("SELECT string_agg(status || ':' || listed_seats || ':' || trigger, ',' ORDER BY listed_seats) FROM enrollment.seat_sync_runs").query(String::class.java).single())
            .isEqualTo("succeeded:1:schedule,succeeded:2:schedule")
        assertThat(vendors.requests(cursorMembers).map { it.header("Authorization") }).containsOnly(basic)
    }

    @Test fun `한 연결의 실패가 다른 연결을 막지 않고, 실패한 연결의 원장은 그대로 낡은 값으로 남는다`() {
        val setup = connectTwo()
        synchronizer.runOnce()
        val before = seats(setup.cursor)

        // Cursor 가 계속 503 — 정한 횟수만큼 시도하고 실패로 남긴다. Claude 는 계속 동기화된다.
        vendors.on("GET", cursorMembers, reply(503, "{}"))
        vendors.on("GET", "/v1/organizations/users", reply(200, """{"data":[],"has_more":false,"first_id":null,"last_id":null}"""))
        clock.now = clock.now.plus(Duration.ofHours(7))
        val attempts = vendors.requests(cursorMembers).size
        assertThat(synchronizer.runOnce()).isEqualTo(SeatSynchronizer.Round(applied = 1, failed = 1))
        assertThat(vendors.requests(cursorMembers).size - attempts).isEqualTo(2)

        assertThat(seats(setup.cursor)).isEqualTo(before)
        val failing = connections.view(tenant, setup.cursor).connection!!.sync
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
        val accepted = manage("POST", "/vendors/${setup.cursor}/connection/sync", null, setup.token, key = key)
        assertThat(accepted.statusCode()).withFailMessage(accepted.body()).isEqualTo(202)
        val body = json(accepted)
        val operationId = body.path("operationId").asString()
        assertThat(listOf(body.path("kind").asString(), body.path("status").asString())).containsExactly("seat_sync", "pending")
        assertThat(accepted.headers().firstValue("Location")).hasValue("/api/v1/organizations/$tenant/operations/$operationId")
        assertThat(json(manage("POST", "/vendors/${setup.cursor}/connection/sync", null, setup.token, key = key)).path("operationId").asString()).isEqualTo(operationId)
        assertThat(json(manage("POST", "/vendors/${setup.cursor}/connection/sync", null, setup.token)).path("operationId").asString())
            .describedAs("끝나지 않은 요청이 있으면 새 키도 같은 작업").isEqualTo(operationId)

        assertThat(synchronizer.runOnce()).describedAs("요청이 걸린 Cursor 만 — Claude 는 아직 차례가 아니다").isEqualTo(SeatSynchronizer.Round(1, 0))
        assertThat(operation(operationId).let { it.status to it.targets.single().status.wire }).isEqualTo(OperationStatus.SUCCEEDED to "succeeded")

        // 실패한 요청은 사유를 남긴다.
        vendors.on("GET", cursorMembers, reply(401, """{"error":"unauthorized"}"""))
        val failedId = json(manage("POST", "/vendors/${setup.cursor}/connection/sync", null, setup.token)).path("operationId").asString()
        synchronizer.runOnce()
        assertThat(operation(failedId).let { it.status to it.targets.single().reason }).isEqualTo(OperationStatus.FAILED to "invalid_credentials")
    }

    @Test fun `연결이 없는 제품의 동기화 요청은 404, 계약을 비우면 커넥터가 맞지 않아 plan_mismatch 로 실패한다`() {
        val setup = connectTwo()
        val openai = vendor("openai_biz", "business", setup.token).path("vendorId").asString()
        assertThat(manage("POST", "/vendors/$openai/connection/sync", null, setup.token).statusCode()).isEqualTo(404)

        assertThat(manage("DELETE", "/vendors/${setup.cursor}/contract", null, setup.token, etag = "\"vendor-${currentVersion(setup.cursor)}\"").statusCode()).isEqualTo(204)
        synchronizer.runOnce()
        assertThat(connections.view(tenant, setup.cursor).connection!!.sync.lastError).isEqualTo("plan_mismatch")
        assertThat(seats(setup.cursor)).isEmpty()
        assertThat(vendors.requests(cursorMembers)).describedAs("플랜이 맞지 않으면 벤더를 부르지 않는다").isEmpty()
    }

    private fun currentVersion(vendorId: String): Long = jdbc.sql("SELECT max(version) FROM enrollment.vendor_contract_versions WHERE vendor_id = :id")
        .param("id", vendorId).query(Long::class.java).single()

    @Test fun `청구 누계 — 같은 실행이 좌석 목록 뒤에 Claude 의 이번 달(서울) 사용 비용과 Cursor 의 이번 주기 지출을 읽어 저장한다`() {
        val setup = connectTwo()
        assertThat(synchronizer.runOnce()).isEqualTo(SeatSynchronizer.Round(applied = 2, failed = 0))
        // 시계 2026-09-09T12:00Z = 서울 9월 9일 → 기간은 서울 9월 1일 0시(= 8월 31일 15시 UTC)부터 지금까지.
        val monthStart = Instant.parse("2026-08-31T15:00:00Z")
        assertThat(billing(setup.claude)).containsExactly(listOf(monthStart, clock.now, "412.8", "usage_cost", false, "connector", clock.now))
        // Cursor 는 벤더가 정한 주기 시작부터의 on-demand 지출(센트 → 달러)이다.
        assertThat(billing(setup.cursor)).containsExactly(listOf(Instant.parse("2026-09-01T00:00:00Z"), clock.now, "12.345", "usage_spend", false, "connector", clock.now))
        with(vendors.requests(costReport).single()) {
            assertThat(listOf(param("starting_at"), param("ending_at"), param("bucket_width"), header("x-api-key"))).containsExactly("2026-08-31T15:00:00Z", clock.now.toString(), "1h", secret)
        }
        val claude = connections.view(tenant, setup.claude).connection!!
        assertThat(claude.billing!!.let { it.status to it.lastSucceededAt }).isEqualTo("succeeded" to clock.now.toString())
        assertThat(connections.view(tenant, setup.cursor).connection!!.billing!!.status).isEqualTo("succeeded")

        // 같은 기간을 다시 읽으면 덮는다(벤더가 고칠 수 있는 값).
        vendors.on("GET", costReport, reply(200, """{"data":[{"results":[{"amount":"50000","currency":"USD"}]}],"has_more":false}"""))
        clock.now = clock.now.plus(Duration.ofHours(7))
        synchronizer.runOnce()
        assertThat(billing(setup.claude).map { it[0] to it[2] }).containsExactly(monthStart to "500")
    }

    @Test fun `청구 읽기의 실패는 좌석 동기화를 실패로 만들지 않고 마지막 누계를 남기며, 좌석 목록이 실패해도 청구는 읽는다`() {
        val setup = connectTwo()
        synchronizer.runOnce()
        vendors.on("GET", costReport, reply(403, """{"type":"error","error":{"type":"permission_error","message":"missing read:analytics"}}"""))
        clock.now = clock.now.plus(Duration.ofHours(7))
        assertThat(synchronizer.runOnce()).describedAs("좌석은 둘 다 반영됐다").isEqualTo(SeatSynchronizer.Round(applied = 2, failed = 0))
        assertThat(billing(setup.claude).map { it[2] }).containsExactly("412.8")
        val failing = connections.view(tenant, setup.claude).connection!!
        assertThat(listOf(failing.billing!!.status, failing.billing!!.lastError, failing.sync.status)).containsExactly("failing", "insufficient_permission", "succeeded")

        // 좌석 목록이 일시 장애여도 같은 실행이 청구를 읽는다.
        vendors.on("GET", "/v1/organizations/users", reply(503, "{}"))
        vendors.on("GET", costReport, reply(200, """{"data":[],"has_more":false}"""))
        clock.now = clock.now.plus(Duration.ofHours(7))
        assertThat(synchronizer.runOnce()).isEqualTo(SeatSynchronizer.Round(applied = 1, failed = 1))
        assertThat(billing(setup.claude).map { it[2] }).describedAs("이번 달 사용이 없으면 0 이다(벤더가 0 을 줬다)").containsExactly("0")
        assertThat(connections.view(tenant, setup.claude).connection!!.let { it.billing!!.status to it.sync.status }).isEqualTo("succeeded" to "failing")
    }
}
