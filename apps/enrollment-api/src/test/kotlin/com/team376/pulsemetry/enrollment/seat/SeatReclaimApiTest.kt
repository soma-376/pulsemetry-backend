package com.team376.pulsemetry.enrollment.seat

import com.team376.pulsemetry.connector.vendor.ControlResult
import com.team376.pulsemetry.connector.vendor.ControlStatus
import com.team376.pulsemetry.connector.vendor.MockVendorServer
import com.team376.pulsemetry.connector.vendor.reply
import com.team376.pulsemetry.enrollment.auth.AbstractUserAuthApiTest
import com.team376.pulsemetry.enrollment.auth.AuthClockConfig
import com.team376.pulsemetry.enrollment.support.EnrollmentTestData
import com.team376.pulsemetry.persistence.enrollment.management.ManagementException
import com.team376.pulsemetry.persistence.enrollment.operation.Operation
import com.team376.pulsemetry.persistence.enrollment.operation.OperationStatus
import com.team376.pulsemetry.persistence.enrollment.operation.OperationStore
import com.team376.pulsemetry.persistence.enrollment.seat.SeatControl
import com.team376.pulsemetry.persistence.enrollment.seat.SeatLedger
import com.team376.pulsemetry.persistence.enrollment.seat.SeatSource
import com.team376.pulsemetry.persistence.enrollment.seat.SeatState
import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
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
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 좌석 회수·복원 (ADR 0049) — 미리보기 → 실행(작업) → 벤더 제어(모의 서버) 또는 관리자 조치 확인 → 원장·작업 대상 결과 → 복원.
 * 기대값은 ADR 의 규칙에서 쓴다: 원장은 벤더가 받아들였거나 관리자가 확인했을 때만 바뀌고, 미리보기 뒤 판·방식이 바뀌면 실행을 거절하며,
 * 실패 대상은 사유와 함께 남고 부분 실패를 성공으로 올리지 않는다. 주기 실행은 테스트가 한 바퀴씩 부른다.
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
class SeatReclaimApiTest : AbstractUserAuthApiTest() {

    companion object {
        val vendors = MockVendorServer()

        @JvmStatic @DynamicPropertySource fun vendorApis(registry: DynamicPropertyRegistry) {
            listOf("claude_enterprise", "cursor_enterprise").forEach { id ->
                registry.add("pulsemetry.vendor-connections.base-urls.$id") { vendors.base.toString() }
            }
        }
    }

    @Autowired private lateinit var controls: SeatControlRunner
    @Autowired private lateinit var synchronizer: SeatSynchronizer
    @Autowired private lateinit var ledger: SeatLedger
    @Autowired private lateinit var manager: PlatformTransactionManager

    private val cursorMembers = "/teams/members"
    private val removeMember = "/teams/remove-member"
    private val secret = "fake-vendor-credential-" + "r".repeat(24)
    private val octo = "octo@example.test"
    private val hubot = "hubot@example.test"
    private val mona = "mona@example.test"

    /** Cursor 구성원 셋(octo·hubot·mona)과 빈 지출. */
    @BeforeEach fun vendorReplies() {
        vendors.received.clear()
        listing(octo, hubot, mona)
        vendors.on("POST", "/teams/spend", reply(200, """{"teamMemberSpend":[],"subscriptionCycleStart":1788220800000,"totalPages":1}"""))
    }

    private fun listing(vararg emails: String) {
        val rows = emails.joinToString(",") { email -> """{"id":"user_${email.substringBefore('@')}","email":"$email","role":"member","isRemoved":false}""" }
        vendors.on("GET", cursorMembers, reply(200, """{"teamMembers":[$rows]}"""))
    }

    private fun json(response: HttpResponse<String>): JsonNode = mapper.readTree(response.body())
    private fun ok(response: HttpResponse<String>, status: Int = 200): JsonNode {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(status)
        return json(response)
    }
    private fun errorOf(response: HttpResponse<String>) = response.statusCode() to json(response).path("error").path("code").asString()
    private fun operation(id: Any) = OperationStore(jdbc, manager, clock).find(tenant, UUID.fromString(id.toString()))!!
    private fun control() = SeatControl(jdbc, manager, clock, mapper, vendorControl = true)

    /** 등록 제품과 계약 — Standard [seats]석 × [fee] 달러. */
    private fun vendor(kind: String, plan: String, token: String, seats: Int = 5, fee: String = "30"): JsonNode {
        val contract = mapOf("planId" to plan, "effectiveFrom" to "2026-09-09", "tiers" to listOf(mapOf("label" to "Standard", "seats" to seats, "monthlyFeePerSeatUsd" to fee)))
        return ok(manage("POST", "/vendors", mapOf("kind" to kind, "displayName" to kind, "contract" to contract), token), 201).path("vendor")
    }

    private fun assign(vendor: JsonNode, account: String, token: String, tier: Boolean = true): JsonNode = ok(manage("POST", "/vendors/${vendor.path("vendorId").asString()}/seats",
        mapOf("account" to account) + if (tier) mapOf("tierId" to vendor.at("/contract/tiers/0/tierId").asString()) else emptyMap(), token), 201).path("seat")

    private fun cursor(token: String): String {
        val vendorId = vendor("cursor", "cursor_enterprise", token, fee = "19").path("vendorId").asString()
        ok(manage("PUT", "/vendors/$vendorId/connection", mapOf("expectedVersion" to 0, "settings" to emptyMap<String, String>(), "credential" to secret), token))
        assertThat(synchronizer.runOnce().applied).isEqualTo(1)
        return vendorId
    }

    private fun seat(vendorId: String, account: String) = ledger.seats(tenant, vendorId).single { it.account == account }
    private fun preview(token: String, vararg seats: Pair<String, Long>, key: String = UUID.randomUUID().toString()) =
        manage("POST", "/seat-reclaims/preview", mapOf("seats" to seats.map { mapOf("seatAssignmentId" to it.first, "expectedVersion" to it.second) }), token, key = key)
    private fun results(operation: Operation) = operation.targets.associate { it.targetId to listOf(it.status.wire, it.reason, it.action) }

    @Test fun `관리자 조치 — 미리보기가 대상마다 다시 검사하고, 실행은 조치 대기로 남아 원장을 바꾸지 않으며, 확인해야 해제·복원된다`() {
        val token = adminToken()
        val claude = vendor("claude_team", "team", token)
        val claudeId = claude.path("vendorId").asString()
        val dana = assign(claude, "dana@example.test", token)
        val eli = assign(claude, "eli@example.test", token)
        val gil = assign(claude, "gil@example.test", token, tier = false)
        ok(manage("POST", "/vendors/$claudeId/seats/${gil.path("seatAssignmentId").asString()}/release", mapOf("expectedVersion" to 1), token))
        val danaId = dana.path("seatAssignmentId").asString()

        val stranger = UUID.randomUUID().toString()
        val body = ok(preview(token, danaId to 1L, eli.path("seatAssignmentId").asString() to 99L, gil.path("seatAssignmentId").asString() to 2L, stranger to 1L, "not-a-uuid" to 1L))
        assertThat(body.path("eligibleSeatAssignmentIds").toList().map { it.asString() }).containsExactly(danaId)
        assertThat(body.path("rejected").toList().associate { it.path("seatAssignmentId").asString() to it.path("reason").asString() }).isEqualTo(mapOf(
            eli.path("seatAssignmentId").asString() to "version_conflict", gil.path("seatAssignmentId").asString() to "not_assigned",
            stranger to "not_found", "not-a-uuid" to "not_found"))
        // 커넥터가 없는 플랜(Team)이라 관리자 조치다. 절감 추정은 dana 좌석 등급의 계약 단가, 회수 뒤 미배정은 5 − (보유 2 − 1).
        assertThat(body.path("targets").toList().map { it.path("method").asString() }).containsExactly("admin_action")
        assertThat(listOf(body.path("estimatedMonthlySavingsUsd").asString(), body.path("savingsBasis").asString(), body.path("resultingUnallocatedSeats").asLong()))
            .containsExactly("30", "contract_unit_price", 4L)
        assertThat(body.path("savingsEffectiveAt").isNull).describedAs("감액 시점은 모른다").isTrue()
        assertThat(Instant.parse(body.path("expiresAt").asString())).isEqualTo(clock.now.plus(Duration.ofMinutes(5)))

        val key = UUID.randomUUID().toString()
        val accepted = manage("POST", "/seat-reclaims", mapOf("previewId" to body.path("previewId").asString()), token, key = key)
        val reclaim = ok(accepted, 202)
        val reclaimId = reclaim.path("operationId").asString()
        assertThat(accepted.headers().firstValue("Location")).hasValue("/api/v1/organizations/$tenant/operations/$reclaimId")
        assertThat(listOf(reclaim.path("kind").asString(), reclaim.path("status").asString(), reclaim.path("canRestore").asBoolean(), reclaim.path("restoreUntil").asString()))
            .containsExactly("seat_reclaim", "awaiting_admin_action", false, clock.now.plus(Duration.ofDays(30)).toString())
        assertThat(reclaim.at("/results/0/action").asString()).isEqualTo("release_in_vendor_console")
        assertThat(seat(claudeId, "dana@example.test").let { it.state to it.version }).describedAs("확인 전에는 원장 불변").isEqualTo(SeatState.ASSIGNED to 1L)
        assertThat(ok(manage("POST", "/seat-reclaims", mapOf("previewId" to body.path("previewId").asString()), token, key = key), 202).path("operationId").asString())
            .describedAs("같은 키의 재시도는 같은 작업").isEqualTo(reclaimId)
        assertThat(errorOf(manage("POST", "/seat-reclaims", mapOf("previewId" to body.path("previewId").asString()), token))).isEqualTo(409 to "preview_used")
        assertThat(ok(preview(token, danaId to 1L)).at("/rejected/0/reason").asString()).describedAs("끝나지 않은 회수가 있는 좌석").isEqualTo("control_in_progress")

        val confirmed = ok(manage("POST", "/operations/$reclaimId/targets/$danaId/confirm", null, token))
        assertThat(listOf(confirmed.path("status").asString(), confirmed.path("canRestore").asBoolean())).containsExactly("succeeded", true)
        assertThat(operation(reclaimId).targets.single().confirmedBy).isEqualTo(member)
        with(seat(claudeId, "dana@example.test")) {
            assertThat(listOf(state, source, version)).containsExactly(SeatState.RELEASED, SeatSource.ADMIN_ACTION, 2L)
        }
        assertThat(ledger.history(tenant, UUID.fromString(danaId)).last().let { it.operationId to it.actorId }).isEqualTo(UUID.fromString(reclaimId) to null)
        assertThat(errorOf(manage("POST", "/operations/$reclaimId/targets/$danaId/confirm", null, token))).isEqualTo(409 to "not_awaiting_admin_action")

        // 복원도 관리자 조치 — 확인해야 다시 배정된다. 한 회수에 살아 있는 복원은 하나다.
        val restore = ok(manage("POST", "/seat-reclaims/$reclaimId/restore", emptyMap<String, Any>(), token), 202)
        val restoreId = restore.path("operationId").asString()
        assertThat(listOf(restore.path("kind").asString(), restore.path("status").asString(), restore.at("/results/0/action").asString()))
            .containsExactly("seat_restore", "awaiting_admin_action", "restore_in_vendor_console")
        assertThat(errorOf(manage("POST", "/seat-reclaims/$reclaimId/restore", emptyMap<String, Any>(), token))).isEqualTo(422 to "restore_not_available")
        assertThat(seat(claudeId, "dana@example.test").state).isEqualTo(SeatState.RELEASED)
        ok(manage("POST", "/operations/$restoreId/targets/$danaId/confirm", null, token))
        assertThat(seat(claudeId, "dana@example.test").let { listOf(it.state, it.source, it.version) }).containsExactly(SeatState.ASSIGNED, SeatSource.ADMIN_ACTION, 3L)
        assertThat(operation(restoreId).status).isEqualTo(OperationStatus.SUCCEEDED)
    }

    @Test fun `미리보기에 묶는다 — 판이 바뀌면 stale, 5분이 지나면 expired, 다른 요청자의 미리보기는 없는 것, 실행할 대상이 없으면 422`() {
        val token = adminToken()
        val claude = vendor("claude_team", "team", token)
        val claudeId = claude.path("vendorId").asString()
        val dana = assign(claude, "dana@example.test", token).path("seatAssignmentId").asString()

        val first = ok(preview(token, dana to 1L)).path("previewId").asString()
        ok(manage("PATCH", "/vendors/$claudeId/seats/$dana", mapOf("expectedVersion" to 1, "note" to "다른 탭의 보정"), token))
        assertThat(errorOf(manage("POST", "/seat-reclaims", mapOf("previewId" to first), token))).isEqualTo(409 to "preview_stale")
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.operations WHERE kind = 'seat_reclaim'").query(Int::class.java).single()).isZero()

        val second = ok(preview(token, dana to 2L)).path("previewId").asString()
        clock.now = clock.now.plus(Duration.ofMinutes(5))
        assertThat(errorOf(manage("POST", "/seat-reclaims", mapOf("previewId" to second), token))).isEqualTo(409 to "preview_expired")

        val third = ok(preview(token, dana to 2L)).path("previewId").asString()
        val otherAdmin = data.member(tenant, "other-admin@example.test").id
        val error = catchThrowableOfType(ManagementException::class.java) { control().execute(tenant, otherAdmin, UUID.fromString(third)) }
        assertThat(error.status to error.code).describedAs("미리보기는 요청자에 묶인다").isEqualTo(404 to "not_found")

        val nothing = ok(preview(token, UUID.randomUUID().toString() to 1L))
        assertThat(nothing.path("eligibleSeatAssignmentIds").isEmpty && nothing.path("estimatedMonthlySavingsUsd").isNull && nothing.path("resultingUnallocatedSeats").isNull).isTrue()
        assertThat(errorOf(manage("POST", "/seat-reclaims", mapOf("previewId" to nothing.path("previewId").asString()), token))).isEqualTo(422 to "no_eligible_seats")
        assertThat(errorOf(preview(token))).isEqualTo(400 to "invalid_request")
        assertThat(errorOf(preview(token, dana to 2L, dana to 2L))).describedAs("같은 좌석 두 번").isEqualTo(400 to "invalid_request")
    }

    @Test fun `벤더 제어 — 받아들인 대상만 원장에 옮기고 실패는 사유로 남아 부분 실패다, 복원은 커넥터가 없어 관리자 조치 확인으로 끝난다`() {
        val token = adminToken()
        val cursor = cursor(token)
        val octoSeat = seat(cursor, octo).id.toString()
        val hubotSeat = seat(cursor, hubot).id.toString()
        val body = ok(preview(token, octoSeat to 1L, hubotSeat to 1L))
        assertThat(body.path("targets").toList().map { it.path("method").asString() }).containsOnly("vendor_control")
        // 동기화한 좌석에는 계약 등급이 없다 — 단가를 모르니 절감 추정은 없다. 회수 뒤 미배정은 5 − (3 − 2).
        assertThat(body.path("estimatedMonthlySavingsUsd").isNull && body.path("savingsBasis").isNull).isTrue()
        assertThat(body.path("resultingUnallocatedSeats").asLong()).isEqualTo(4)
        val reclaim = ok(manage("POST", "/seat-reclaims", mapOf("previewId" to body.path("previewId").asString()), token), 202)
        val reclaimId = reclaim.path("operationId").asString()
        assertThat(reclaim.path("status").asString()).isEqualTo("running")
        assertThat(vendors.requests(removeMember)).describedAs("접수만 했다 — 벤더 호출은 주기 실행이 한다").isEmpty()

        vendors.on("POST", removeMember, { request ->
            if (request.body.contains("user_hubot")) MockVendorServer.Reply(403, """{"error":"forbidden"}""")
            else MockVendorServer.Reply(200, """{"success":true,"userId":"user_octo","hasBillingCycleUsage":false}""")
        })
        assertThat(controls.runOnce()).isEqualTo(SeatControlRunner.Round(completed = 1, failed = 1))
        with(operation(reclaimId)) {
            assertThat(status).isEqualTo(OperationStatus.PARTIALLY_FAILED)
            assertThat(results(this)).isEqualTo(mapOf(octoSeat to listOf("succeeded", null, null), hubotSeat to listOf("failed", "insufficient_permission", null)))
            assertThat(canRestore(clock.now)).isTrue()
        }
        // Cursor 제거는 그 자리에서 끝난다(벤더 응답 success) — 해제다. 벤더 내부 ID 로 불렀다.
        with(seat(cursor, octo)) { assertThat(listOf(state, source, releaseEffectiveOn)).containsExactly(SeatState.RELEASED, SeatSource.VENDOR_CONTROL, null) }
        assertThat(ledger.history(tenant, UUID.fromString(octoSeat)).last().operationId).isEqualTo(UUID.fromString(reclaimId))
        assertThat(seat(cursor, hubot).let { it.state to it.version }).describedAs("실패한 대상의 원장은 그대로").isEqualTo(SeatState.ASSIGNED to 1L)
        assertThat(vendors.requests(removeMember).map { it.header("Authorization") })
            .containsOnly("Basic " + java.util.Base64.getEncoder().encodeToString("$secret:".toByteArray()))
        assertThat(vendors.requests(removeMember).map { mapper.readTree(it.body).path("userId").asString() }).containsExactlyInAnyOrder("user_octo", "user_hubot")

        // 복원 — 회수에서 성공한 octo 만. 복원을 구현한 커넥터가 없어(ADR 0049·0054) 관리자가 벤더 콘솔에서 하고 확인한다. 시계가 7시간 흘러 다시 로그인한다.
        clock.now = clock.now.plus(Duration.ofHours(7))
        val fresh = mapper.readTree(login().body()).path("access_token").asString()
        val restore = ok(manage("POST", "/seat-reclaims/$reclaimId/restore", emptyMap<String, Any>(), fresh), 202)
        val restoreId = restore.path("operationId").asString()
        assertThat(listOf(restore.path("status").asString(), restore.at("/results/0/targetId").asString(), restore.at("/results/0/action").asString()))
            .containsExactly("awaiting_admin_action", octoSeat, "restore_in_vendor_console")
        val calls = vendors.received.size
        assertThat(controls.runOnce()).describedAs("관리자 조치는 벤더를 부르지 않는다").isEqualTo(SeatControlRunner.Round(0, 0))
        assertThat(vendors.received).hasSize(calls)
        assertThat(seat(cursor, octo).state).describedAs("확인 전에는 원장 불변").isEqualTo(SeatState.RELEASED)
        ok(manage("POST", "/operations/$restoreId/targets/$octoSeat/confirm", null, fresh))
        with(seat(cursor, octo)) { assertThat(listOf(state, source)).containsExactly(SeatState.ASSIGNED, SeatSource.ADMIN_ACTION) }
        assertThat(operation(restoreId).status).isEqualTo(OperationStatus.SUCCEEDED)
        assertThat(operation(reclaimId).canRestore(clock.now)).describedAs("되돌리는 중(끝난 복원)이 있다").isFalse()
    }

    @Test fun `벤더 제어 — 모두 실패하면 실패이고 되돌릴 것이 없으며, 호출 전에 좌석이 바뀌었으면 벤더를 부르지 않는다`() {
        val token = adminToken()
        val cursor = cursor(token)
        val octoSeat = seat(cursor, octo).id.toString()
        val monaSeat = seat(cursor, mona).id.toString()
        vendors.on("POST", removeMember, reply(500, "{}"))
        val reclaimId = ok(manage("POST", "/seat-reclaims", mapOf("previewId" to ok(preview(token, octoSeat to 1L)).path("previewId").asString()), token), 202).path("operationId").asString()
        assertThat(controls.runOnce()).isEqualTo(SeatControlRunner.Round(0, 1))
        assertThat(vendors.requests(removeMember)).describedAs("일시 장애는 정한 횟수만큼 시도한다").hasSize(2)
        with(operation(reclaimId)) {
            assertThat(status to results(this)).isEqualTo(OperationStatus.FAILED to mapOf(octoSeat to listOf("failed", "vendor_unavailable", null)))
            assertThat(canRestore(clock.now)).isFalse()
        }
        assertThat(seat(cursor, octo).state).isEqualTo(SeatState.ASSIGNED)
        assertThat(errorOf(manage("POST", "/seat-reclaims/$reclaimId/restore", emptyMap<String, Any>(), token))).isEqualTo(422 to "restore_not_available")

        // 실행 뒤, 벤더 호출 전에 동기화가 mona 를 해제했다 — 제어할 좌석이 아니므로 부르지 않는다.
        val second = ok(manage("POST", "/seat-reclaims", mapOf("previewId" to ok(preview(token, monaSeat to 1L)).path("previewId").asString()), token), 202).path("operationId").asString()
        listing(octo, hubot)
        clock.now = clock.now.plus(Duration.ofHours(7))
        synchronizer.runOnce()
        val calls = vendors.requests(removeMember).size
        assertThat(controls.runOnce()).isEqualTo(SeatControlRunner.Round(0, 0))
        assertThat(vendors.requests(removeMember)).hasSize(calls)
        assertThat(results(operation(second))).isEqualTo(mapOf(monaSeat to listOf("failed", "seat_changed", null)))
    }

    @Test fun `선점 — 기한 안의 대상은 다른 실행이 가져가지 못하고, 기한이 지나 넘어간 대상의 옛 선점은 결과를 쓰지 못한다`() {
        val token = adminToken()
        val cursor = cursor(token)
        val octoSeat = seat(cursor, octo).id.toString()
        ok(manage("POST", "/seat-reclaims", mapOf("previewId" to ok(preview(token, octoSeat to 1L)).path("previewId").asString()), token), 202)
        val first = control().claim("worker-a", Duration.ofMinutes(5))!!
        assertThat(first.account.account).isEqualTo(octo)
        assertThat(control().claim("worker-b", Duration.ofMinutes(5))).isNull()
        clock.now = clock.now.plus(Duration.ofMinutes(6))
        val second = control().claim("worker-b", Duration.ofMinutes(5))!!
        assertThat(control().complete(first, ControlResult(ControlStatus.COMPLETED))).isFalse()
        assertThat(seat(cursor, octo).state).isEqualTo(SeatState.ASSIGNED)
        assertThat(control().complete(second, ControlResult(ControlStatus.COMPLETED))).isTrue()
        assertThat(seat(cursor, octo).let { it.state to it.source }).isEqualTo(SeatState.RELEASED to SeatSource.VENDOR_CONTROL)
        assertThat(jdbc.sql("SELECT attempts FROM enrollment.seat_controls").query(Int::class.java).single()).isEqualTo(2)
    }

    @Test fun `조직 경계와 권한 — 구성원(member)은 회수하지 못하고, 다른 조직의 작업·좌석은 없는 것이며, 조치 취소는 원장을 바꾸지 않는다`() {
        val memberToken = tokens().path("access_token").asString()
        assertThat(errorOf(preview(memberToken, UUID.randomUUID().toString() to 1L))).isEqualTo(403 to "forbidden")
        jdbc.sql("UPDATE enrollment.members SET role='owner' WHERE id=:id").param("id", member).update()
        val token = mapper.readTree(login().body()).path("access_token").asString()
        val claude = vendor("claude_team", "team", token)
        val dana = assign(claude, "dana@example.test", token).path("seatAssignmentId").asString()
        val reclaimId = ok(manage("POST", "/seat-reclaims", mapOf("previewId" to ok(preview(token, dana to 1L)).path("previewId").asString()), token), 202).path("operationId").asString()

        val other = data.tenant().id
        val otherAdmin = data.member(other, "owner@other.example.test").id
        val error = catchThrowableOfType(ManagementException::class.java) { control().confirm(other, otherAdmin, UUID.fromString(reclaimId), dana) }
        assertThat(error.status to error.code).isEqualTo(404 to "not_found")
        assertThat(catchThrowableOfType(ManagementException::class.java) { control().restore(other, otherAdmin, UUID.fromString(reclaimId)) }.status).isEqualTo(404)
        val foreign = http.send(HttpRequest.newBuilder(URI("http://localhost:$port/api/v1/organizations/$other/operations/$reclaimId/targets/$dana/confirm"))
            .header("Authorization", "Bearer $token").header("Idempotency-Key", UUID.randomUUID().toString()).POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString())
        assertThat(errorOf(foreign)).isEqualTo(404 to "not_found")
        assertThat(errorOf(manage("POST", "/operations/$reclaimId/targets/${UUID.randomUUID()}/confirm", null, token))).isEqualTo(404 to "not_found")

        val cancelled = ok(manage("POST", "/operations/$reclaimId/targets/$dana/cancel", null, token))
        assertThat(listOf(cancelled.path("status").asString(), cancelled.at("/results/0/reason").asString(), cancelled.path("canRestore").asBoolean()))
            .containsExactly("failed", "cancelled", false)
        assertThat(ledger.seat(tenant, UUID.fromString(dana))!!.let { it.state to it.version }).isEqualTo(SeatState.ASSIGNED to 1L)
        assertThat(errorOf(manage("POST", "/operations/$reclaimId/targets/$dana/confirm", null, token))).isEqualTo(409 to "not_awaiting_admin_action")
    }

    @Test fun `경계 — 미리보기는 4분 59초에도 실행되고, 복원은 기한 직전까지 되며 기한 시각부터 422 다`() {
        var token = adminToken()
        val claude = vendor("claude_team", "team", token)
        val dana = assign(claude, "dana@example.test", token).path("seatAssignmentId").asString()
        val eli = assign(claude, "eli@example.test", token).path("seatAssignmentId").asString()

        // 미리보기는 5분(ADR 0049 §1) — 4분 59초에는 아직 유효하다.
        val danaPreview = ok(preview(token, dana to 1L)).path("previewId").asString()
        val eliPreview = ok(preview(token, eli to 1L)).path("previewId").asString()
        clock.now = clock.now.plus(Duration.ofMinutes(5)).minusSeconds(1)
        val first = ok(manage("POST", "/seat-reclaims", mapOf("previewId" to danaPreview), token), 202).path("operationId").asString()
        val second = ok(manage("POST", "/seat-reclaims", mapOf("previewId" to eliPreview), token), 202).path("operationId").asString()
        ok(manage("POST", "/operations/$first/targets/$dana/confirm", null, token))
        ok(manage("POST", "/operations/$second/targets/$eli/confirm", null, token))
        val until = operation(first).restoreUntil!!
        assertThat(operation(second).restoreUntil).isEqualTo(until)

        // 복원 기한(만든 시각 + 30일, ADR 0049 §5) — 직전에는 되고, 그 시각부터는 되돌릴 수 없는 회수다. 사이에 토큰이 만료되니 다시 로그인한다.
        clock.now = until.minusMillis(1)
        token = mapper.readTree(login().body()).path("access_token").asString()
        assertThat(ok(manage("POST", "/seat-reclaims/$first/restore", emptyMap<String, Any>(), token), 202).path("kind").asString()).isEqualTo("seat_restore")
        clock.now = until
        assertThat(errorOf(manage("POST", "/seat-reclaims/$second/restore", emptyMap<String, Any>(), token))).isEqualTo(422 to "restore_not_available")
        assertThat(seat(claude.path("vendorId").asString(), "eli@example.test").state).isEqualTo(SeatState.RELEASED)
    }
}
