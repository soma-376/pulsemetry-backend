package com.team376.pulsemetry.persistence.enrollment.seat

import com.team376.pulsemetry.connector.vendor.ConnectorDescriptor
import com.team376.pulsemetry.connector.vendor.ConnectorDescriptors
import com.team376.pulsemetry.connector.vendor.VendorSeat
import com.team376.pulsemetry.connector.vendor.VendorSeatState
import com.team376.pulsemetry.persistence.enrollment.management.ManagementException
import com.team376.pulsemetry.persistence.enrollment.operation.OperationKind
import com.team376.pulsemetry.persistence.enrollment.operation.OperationStatus
import com.team376.pulsemetry.persistence.enrollment.operation.OperationStore
import com.team376.pulsemetry.persistence.enrollment.seat.SeatLedger.Change
import com.team376.pulsemetry.persistence.enrollment.seat.SeatLedger.MemberChoice
import com.team376.pulsemetry.persistence.enrollment.support.AbstractPersistenceIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/**
 * 좌석 원장 (ADR 0048). 기대값은 ADR 의 표와 문장에서 쓴다 — §2 상태·판·이력, §3 우선순위 표의 네 행, §4 구성원 연결, §5 등급, §7 중복 실행 방지.
 *
 * 조직 하나에 등록 제품 셋: OpenAI Business(커넥터 없는 플랜 — 1행), Cursor Enterprise(커넥터 플랜 — 2·3·4행),
 * Claude Enterprise(커넥터 플랜, 벤더 내부 ID). 계정은 모두 이메일이다(ADR 0054). 계약의 구매 수량은 좌석이 아니다.
 */
class SeatLedgerTest : AbstractPersistenceIntegrationTest() {
	@Autowired private lateinit var jdbc: JdbcClient
	@Autowired private lateinit var manager: PlatformTransactionManager

	private class TestClock(var now: Instant) : Clock() {
		override fun getZone(): ZoneId = ZoneOffset.UTC
		override fun withZone(zone: ZoneId?): Clock = this
		override fun instant(): Instant = now
	}

	private val clock = TestClock(Instant.parse("2026-10-01T00:00:00Z"))
	private val mapper = JsonMapper.builder().build()
	private val cipher = CredentialCipher(mapOf("k1" to Base64.getEncoder().encodeToString(ByteArray(32) { 7 })), "k1")
	private lateinit var ledger: SeatLedger
	private lateinit var connections: VendorConnectionStore
	private val tenants = mutableListOf<UUID>()
	private lateinit var tenant: UUID
	private lateinit var admin: UUID
	private lateinit var dana: UUID
	private val openai = "vendor-openai"
	private val cursor = "vendor-cursor"
	private val claude = "vendor-claude"
	private val every: (String, String?) -> ConnectorDescriptor? = ConnectorDescriptors::forPlan

	@BeforeEach
	fun setUp() {
		ledger = SeatLedger(jdbc, manager, clock)
		connections = VendorConnectionStore(jdbc, manager, clock, cipher, mapper)
		tenant = tenant()
		admin = member(tenant, "admin@seat.example.test", "admin")
		dana = member(tenant, "dana@seat.example.test", "member")
		register(tenant, openai, "openai_biz", "business", 5)
		register(tenant, cursor, "cursor", "cursor_enterprise", 3)
		register(tenant, claude, "claude_team", "enterprise", 2)
	}

	@AfterEach
	fun cleanUp() {
		// 다른 조직의 계약 버전이 이 조직의 구성원을 가리킬 수 있어 표마다 모든 조직을 먼저 지운다.
		tenants.forEach { jdbc.sql("DELETE FROM enrollment.operation_targets WHERE operation_id IN (SELECT id FROM enrollment.operations WHERE tenant_id = :t)").param("t", it).update() }
		listOf("seat_assignment_events", "seat_assignments", "seat_sync_runs", "vendor_connections", "operations", "vendor_contract_versions", "managed_vendors", "members").forEach { table ->
			tenants.forEach { jdbc.sql("DELETE FROM enrollment.$table WHERE tenant_id = :t").param("t", it).update() }
		}
		tenants.forEach { jdbc.sql("DELETE FROM enrollment.tenants WHERE id = :t").param("t", it).update() }
	}

	private fun tenant(): UUID = UUID.randomUUID().also {
		jdbc.sql("INSERT INTO enrollment.tenants(id,name) VALUES (:id,'좌석 원장 테스트 조직')").param("id", it).update()
		tenants += it
	}

	private fun member(tenantId: UUID, email: String, role: String): UUID = UUID.randomUUID().also {
		jdbc.sql("INSERT INTO enrollment.members(id,tenant_id,email,role) VALUES (:id,:tenant,:email,CAST(:role AS enrollment.member_role))")
			.param("id", it).param("tenant", tenantId).param("email", email).param("role", role).update()
	}

	private fun tier(vendorId: String) = "tier-$vendorId"

	private fun register(tenantId: UUID, vendorId: String, kind: String, plan: String, seats: Int) {
		jdbc.sql("INSERT INTO enrollment.managed_vendors (tenant_id,vendor_id,kind,source,created_at) VALUES (:t,:id,:kind,'manual',now())")
			.param("t", tenantId).param("id", vendorId).param("kind", kind).update()
		val contract = """{"version":1,"planId":"$plan","effectiveFrom":"2026-01-01","effectiveTo":null,"termNote":null,
			"tiers":[{"tierId":"${tier(vendorId)}","label":"표준","seats":$seats,"monthlyFeePerSeatUsd":"30"}],"monthlySeatFeeUsd":"${30 * seats}","confirmedAt":"2026-01-01T00:00:00Z","confirmedBy":"$admin"}"""
		jdbc.sql("INSERT INTO enrollment.vendor_contract_versions (tenant_id,vendor_id,version,display_name,contract,recorded_at,recorded_by) VALUES (:t,:id,1,:kind,CAST(:c AS jsonb),now(),:admin)")
			.param("t", tenantId).param("id", vendorId).param("kind", kind).param("c", contract).param("admin", admin).update()
	}

	private fun connect(vendorId: String, settings: Map<String, String> = emptyMap()) =
		connections.save(tenant, admin, vendorId, settings, "fake-vendor-credential-" + "s".repeat(36), 0, every)

	private fun code(block: () -> Unit): Pair<String, Int> = try { block(); "ok" to 200 } catch (e: ManagementException) { e.code to e.status }

	private fun run(vendorId: String, worker: String = "worker-a", lease: Duration = Duration.ofMinutes(5)): SeatLedger.SyncRun =
		ledger.startRun(tenant, connections.find(tenant, vendorId)!!.id, worker, lease)!!

	@Test
	fun `구매 수량은 좌석이 아니다 — 계약만 있는 제품의 원장은 비어 있다`() {
		assertThat(listOf(openai, cursor, claude).flatMap { ledger.seats(tenant, it) }).isEmpty()
	}

	@Test
	fun `1행 — 커넥터가 없는 플랜은 수동 기록이 권위다 · 배정·해제·재배정이 같은 좌석 ID 의 판과 이력으로 남는다`() {
		val first = ledger.assign(tenant, openai, admin, SeatSource.MANUAL, " Dana@Seat.Example.TEST ", tierId = tier(openai), note = "첫 배정")
		assertThat(first.account).isEqualTo("dana@seat.example.test")
		assertThat(listOf(first.state, first.source, first.version)).containsExactly(SeatState.ASSIGNED, SeatSource.MANUAL, 1L)
		// 이메일이 정확히 한 구성원과 같으면 잇는다(근거 email_match).
		assertThat(first.memberId to first.memberLink).isEqualTo(dana to MemberLink.EMAIL_MATCH)

		clock.now = clock.now.plusSeconds(60)
		val released = ledger.release(tenant, first.id, admin, SeatSource.CSV, expectedVersion = 1)
		assertThat(listOf(released.state, released.source, released.version, released.releasedAt)).containsExactly(SeatState.RELEASED, SeatSource.CSV, 2L, clock.now)

		clock.now = clock.now.plusSeconds(60)
		val again = ledger.assign(tenant, openai, admin, SeatSource.MANUAL, "dana@seat.example.test", expectedVersion = 2)
		assertThat(again.id).isEqualTo(first.id)
		assertThat(listOf(again.state, again.version, again.assignedAt, again.releasedAt)).containsExactly(SeatState.ASSIGNED, 3L, clock.now, null)

		val history = ledger.history(tenant, first.id)
		assertThat(history.map { Triple(it.version, it.state, it.source) }).containsExactly(
			Triple(1L, SeatState.ASSIGNED, SeatSource.MANUAL), Triple(2L, SeatState.RELEASED, SeatSource.CSV), Triple(3L, SeatState.ASSIGNED, SeatSource.MANUAL))
		assertThat(history.map { it.actorId }).containsOnly(admin)
		assertThat(history.first().note).isEqualTo("첫 배정")
		assertThat(SeatSourceView.of("openai_biz", "business", null).let { it.authority to it.provisional }).isEqualTo("manual" to false)
	}

	@Test
	fun `판이 다르면 409 이고 보유 중인 좌석의 재배정·해제된 좌석의 해제는 전이 표 밖이라 거부한다`() {
		val seat = ledger.assign(tenant, openai, admin, SeatSource.MANUAL, "dana@seat.example.test")
		assertThat(code { ledger.release(tenant, seat.id, admin, SeatSource.MANUAL, expectedVersion = 7) }).isEqualTo("version_conflict" to 409)
		assertThat(code { ledger.assign(tenant, openai, admin, SeatSource.MANUAL, "dana@seat.example.test", expectedVersion = 1) }).isEqualTo("seat_already_held" to 409)
		assertThat(code { ledger.assign(tenant, openai, admin, SeatSource.MANUAL, "dana@seat.example.test") }).isEqualTo("version_conflict" to 409)
		ledger.release(tenant, seat.id, admin, SeatSource.MANUAL, 1)
		assertThat(code { ledger.release(tenant, seat.id, admin, SeatSource.MANUAL, 2) }).isEqualTo("seat_not_releasable" to 409)
		assertThat(ledger.history(tenant, seat.id)).hasSize(2)
	}

	@Test
	fun `입력 검증 — 계정 형식·계약 등급·메모·구성원의 조직`() {
		assertThat(code { ledger.assign(tenant, openai, admin, SeatSource.MANUAL, "not-an-email") }).isEqualTo("invalid_request" to 400)
		// 연동 대상이 아닌 제품(ADR 0054)도 계정은 이메일이다 — GitHub 로그인은 받지 않는다.
		register(tenant, "vendor-copilot", "copilot", "copilot_business", 1)
		assertThat(code { ledger.assign(tenant, "vendor-copilot", admin, SeatSource.MANUAL, "octocat") }).describedAs("로그인은 계정이 아니다").isEqualTo("invalid_request" to 400)
		assertThat(ledger.seats(tenant, "vendor-copilot")).isEmpty()
		assertThat(code { ledger.assign(tenant, openai, admin, SeatSource.MANUAL, "dana@seat.example.test", tierId = "tier-other") }).isEqualTo("invalid_tier" to 422)
		assertThat(code { ledger.assign(tenant, openai, admin, SeatSource.MANUAL, "dana@seat.example.test", note = " ") }).isEqualTo("invalid_request" to 400)
		val stranger = member(tenant(), "stranger@seat.example.test", "member")
		assertThat(code { ledger.assign(tenant, openai, admin, SeatSource.MANUAL, "dana@seat.example.test", member = MemberChoice.Member(stranger)) }).isEqualTo("not_found" to 404)
		assertThat(ledger.seats(tenant, openai)).isEmpty()
	}

	@Test
	fun `조직 경계와 권한 — 다른 조직의 제품·좌석은 없는 것이고 구성원(member)·다른 조직의 관리자는 기록하지 못한다`() {
		val seat = ledger.assign(tenant, openai, admin, SeatSource.MANUAL, "dana@seat.example.test")
		val other = tenant()
		val otherAdmin = member(other, "admin@other.example.test", "admin")
		register(other, "vendor-other", "openai_biz", "business", 1)
		assertThat(code { ledger.release(other, seat.id, otherAdmin, SeatSource.MANUAL, 1) }).isEqualTo("not_found" to 404)
		assertThat(code { ledger.assign(other, openai, otherAdmin, SeatSource.MANUAL, "x@other.example.test") }).isEqualTo("not_found" to 404)
		assertThat(code { ledger.assign(tenant, openai, otherAdmin, SeatSource.MANUAL, "x@seat.example.test") }).isEqualTo("forbidden" to 403)
		assertThat(code { ledger.assign(tenant, openai, dana, SeatSource.MANUAL, "x@seat.example.test") }).isEqualTo("forbidden" to 403)
		assertThat(code { ledger.correct(tenant, seat.id, dana, 1, note = Change("메모")) }).isEqualTo("forbidden" to 403)
		assertThat(ledger.seat(other, seat.id)).isNull()
		assertThat(ledger.seats(tenant, openai).single().version).isEqualTo(1)
	}

	@Test
	fun `2행 — 커넥터 플랜이지만 연결이 없으면 수동 기록이 임시 권위다`() {
		val seat = ledger.assign(tenant, cursor, admin, SeatSource.MANUAL, "Dana@Seat.Example.TEST")
		assertThat(listOf(seat.account, seat.memberId, seat.memberLink, seat.accountEmail)).containsExactly("dana@seat.example.test", dana, MemberLink.EMAIL_MATCH, "dana@seat.example.test")
		assertThat(SeatSourceView.of("cursor", "cursor_enterprise", null).let { Triple(it.authority, it.provisional, it.connector?.connectorId) })
			.isEqualTo(Triple("manual", true, "cursor_enterprise"))
		// 연동 대상이 아닌 제품은 커넥터가 없는 플랜이라 수동이 주 원천이다(1행, ADR 0054).
		assertThat(SeatSourceView.of("copilot", "copilot_business", null).let { Triple(it.authority, it.provisional, it.connector) }).isEqualTo(Triple("manual", false, null))
	}

	@Test
	fun `3행 — 연결이 있으면 수동 배정·해제는 거부되고 보정만 된다 · 성공한 동기화가 목록 전체로 수동 행까지 맞춘다`() {
		val kept = ledger.assign(tenant, cursor, admin, SeatSource.MANUAL, "octo@seat.example.test", member = MemberChoice.Member(dana), tierId = tier(cursor), note = "수동 기록")
		val gone = ledger.assign(tenant, cursor, admin, SeatSource.CSV, "hubot@seat.example.test")
		val connection = connect(cursor)
		assertThat(SeatSourceView.of("cursor", "cursor_enterprise", connection).let { it.authority to it.provisional }).isEqualTo("connector" to false)

		assertThat(code { ledger.assign(tenant, cursor, admin, SeatSource.MANUAL, "mona@seat.example.test") }).isEqualTo("connector_managed" to 409)
		assertThat(code { ledger.release(tenant, kept.id, admin, SeatSource.MANUAL, 1) }).isEqualTo("connector_managed" to 409)
		// 보정은 된다 — 상태·원천은 그대로, 판은 오른다.
		val noted = ledger.correct(tenant, gone.id, admin, 1, note = Change("확인 필요"))
		assertThat(listOf(noted.state, noted.source, noted.version, noted.note)).containsExactly(SeatState.ASSIGNED, SeatSource.CSV, 2L, "확인 필요")

		clock.now = clock.now.plusSeconds(3600)
		val sync = run(cursor)
		val result = ledger.applyListing(sync, listOf(
			VendorSeat("Octo@Seat.Example.TEST", state = VendorSeatState.PENDING_RELEASE, releaseEffectiveOn = LocalDate.parse("2026-10-31"),
				assignedAt = Instant.parse("2024-10-01T19:32:20Z"), lastActivityAt = Instant.parse("2026-09-30T01:02:03.456789123Z")),
			VendorSeat("mona@seat.example.test"),
		))
		assertThat(result).isEqualTo(SeatLedger.SyncResult.Applied(listed = 2, changed = 3))

		val byAccount = ledger.seats(tenant, cursor).associateBy { it.account }
		// 같은 계정의 수동 행은 원천이 connector 가 되고 벤더 상태를 받는다. 관리자가 정한 연결·계약 등급·메모는 덮지 않는다.
		with(byAccount.getValue("octo@seat.example.test")) {
			assertThat(listOf(id, state, source, version, releaseEffectiveOn)).containsExactly(kept.id, SeatState.PENDING_RELEASE, SeatSource.CONNECTOR, 2L, LocalDate.parse("2026-10-31"))
			assertThat(listOf(memberId, memberLink, tierId, note)).containsExactly(dana, MemberLink.ADMIN, tier(cursor), "수동 기록")
			assertThat(assignedAt).describedAs("원천이 커넥터로 바뀌면 벤더의 배정 시각").isEqualTo(Instant.parse("2024-10-01T19:32:20Z"))
		}
		// 목록에 없는 보유 좌석은 수동 원천이어도 해제된다.
		with(byAccount.getValue("hubot@seat.example.test")) {
			assertThat(listOf(state, source, version, releasedAt)).containsExactly(SeatState.RELEASED, SeatSource.CONNECTOR, 3L, clock.now)
		}
		with(byAccount.getValue("mona@seat.example.test")) {
			assertThat(listOf(state, source, version, memberId, memberLink, assignedAt)).containsExactly(SeatState.ASSIGNED, SeatSource.CONNECTOR, 1L, null, null, clock.now)
		}
		assertThat(ledger.history(tenant, kept.id).last().let { it.syncRunId to it.actorId }).isEqualTo(sync.id to null)
		val after = connections.find(tenant, cursor)!!
		assertThat(listOf(after.lastSyncSucceededAt, after.lastSyncFailedAt, after.syncStatus)).containsExactly(clock.now, null, SyncStatus.SUCCEEDED)
		assertThat(jdbc.sql("SELECT status, listed_seats, changed_seats FROM enrollment.seat_sync_runs WHERE id = :id").param("id", sync.id)
			.query { rs, _ -> Triple(rs.getString(1), rs.getInt(2), rs.getInt(3)) }.single()).isEqualTo(Triple("succeeded", 2, 3))

		// 같은 목록을 다시 받으면 판이 오르지 않는다. 활동 시각만 바뀌어도 판·이력은 그대로다(관측).
		clock.now = clock.now.plusSeconds(3600)
		val again = ledger.applyListing(run(cursor), listOf(
			VendorSeat("octo@seat.example.test", state = VendorSeatState.PENDING_RELEASE, releaseEffectiveOn = LocalDate.parse("2026-10-31"),
				assignedAt = Instant.parse("2024-10-01T19:32:20Z"), lastActivityAt = Instant.parse("2026-10-01T00:30:00Z")),
			VendorSeat("mona@seat.example.test"),
		))
		assertThat(again).isEqualTo(SeatLedger.SyncResult.Applied(listed = 2, changed = 0))
		assertThat(ledger.seat(tenant, kept.id)!!.let { it.version to it.vendorLastActivityAt }).isEqualTo(2L to Instant.parse("2026-10-01T00:30:00Z"))
	}

	@Test
	fun `4행 — 실패한 동기화는 원장을 바꾸지 않고 연결을 실패 중으로 남긴다 · 형식이 맞지 않는 목록도 반영하지 않는다`() {
		connect(cursor)
		ledger.applyListing(run(cursor), listOf(VendorSeat("octo@seat.example.test")))
		val before = ledger.seats(tenant, cursor)

		clock.now = clock.now.plusSeconds(600)
		assertThat(ledger.failRun(run(cursor), "vendor_unavailable")).isTrue()
		assertThat(ledger.seats(tenant, cursor)).isEqualTo(before)
		val failing = connections.find(tenant, cursor)!!
		assertThat(listOf(failing.syncStatus, failing.lastSyncError, failing.lastSyncFailedAt)).containsExactly(SyncStatus.FAILING, "vendor_unavailable", clock.now)
		assertThat(SeatSourceView.of("cursor", "cursor_enterprise", failing).let { it.authority to it.connection!!.sync.status }).isEqualTo("connector" to "failing")

		clock.now = clock.now.plusSeconds(600)
		assertThat(ledger.applyListing(run(cursor), listOf(VendorSeat("octo@seat.example.test"), VendorSeat("OCTO@SEAT.EXAMPLE.TEST")))).isEqualTo(SeatLedger.SyncResult.Rejected("invalid_listing"))
		assertThat(ledger.applyListing(run(cursor), listOf(VendorSeat("not an email")))).isEqualTo(SeatLedger.SyncResult.Rejected("invalid_listing"))
		assertThat(ledger.seats(tenant, cursor)).isEqualTo(before)
		assertThat(connections.find(tenant, cursor)!!.lastSyncError).isEqualTo("invalid_listing")
	}

	@Test
	fun `구성원 연결 — 이메일이 정확히 한 구성원일 때만 잇고 관리자 연결·관리자의 잇지 않음은 동기화가 바꾸지 않는다`() {
		member(tenant, "Twin@seat.example.test", "member")
		member(tenant, "twin@seat.example.test", "member")
		connect(claude, emptyMap())
		ledger.applyListing(run(claude), listOf(
			VendorSeat("dana@seat.example.test", vendorAccountRef = "user_01", email = "dana@seat.example.test"),
			VendorSeat("twin@seat.example.test", vendorAccountRef = "user_02"),
			VendorSeat("nobody@seat.example.test", vendorAccountRef = "user_03"),
		))
		val seats = ledger.seats(tenant, claude).associateBy { it.account }
		assertThat(seats.getValue("dana@seat.example.test").let { Triple(it.memberId, it.memberLink, it.vendorAccountRef) }).isEqualTo(Triple(dana, MemberLink.EMAIL_MATCH, "user_01"))
		assertThat(seats.getValue("twin@seat.example.test").memberId).describedAs("같은 이메일이 둘이면 잇지 않는다").isNull()
		assertThat(seats.getValue("nobody@seat.example.test").memberId).isNull()

		// 관리자가 dana 의 좌석을 '잇지 않음'으로, nobody 의 좌석을 dana 로 정한다.
		ledger.correct(tenant, seats.getValue("dana@seat.example.test").id, admin, 1, member = MemberChoice.Unlinked)
		ledger.correct(tenant, seats.getValue("nobody@seat.example.test").id, admin, 1, member = MemberChoice.Member(dana))
		clock.now = clock.now.plusSeconds(60)
		ledger.applyListing(run(claude), listOf(
			VendorSeat("dana@seat.example.test", vendorAccountRef = "user_01"), VendorSeat("nobody@seat.example.test", vendorAccountRef = "user_03"),
		))
		val after = ledger.seats(tenant, claude).associateBy { it.account }
		assertThat(after.getValue("dana@seat.example.test").let { it.memberId to it.memberLink }).isEqualTo(null to MemberLink.ADMIN)
		assertThat(after.getValue("nobody@seat.example.test").let { it.memberId to it.memberLink }).isEqualTo(dana to MemberLink.ADMIN)

		// 자동으로 되돌리면 이메일 일치 규칙이 다시 정한다.
		val restored = ledger.correct(tenant, after.getValue("dana@seat.example.test").id, admin, after.getValue("dana@seat.example.test").version, member = MemberChoice.Automatic)
		assertThat(restored.memberId to restored.memberLink).isEqualTo(dana to MemberLink.EMAIL_MATCH)
		assertThat(ledger.correct(tenant, restored.id, admin, restored.version, member = MemberChoice.Automatic).version).describedAs("바뀐 것이 없으면 판이 그대로").isEqualTo(restored.version)
	}

	@Test
	fun `연결을 지우면 원장은 남고 권위가 수동으로 돌아간다 — 커넥터 원천 행을 수동으로 고칠 수 있다`() {
		val connection = connect(cursor)
		ledger.applyListing(run(cursor), listOf(VendorSeat("octo@seat.example.test")))
		connections.delete(tenant, admin, cursor, connection.version)
		val seat = ledger.seats(tenant, cursor).single()
		assertThat(seat.source).isEqualTo(SeatSource.CONNECTOR)
		val released = ledger.release(tenant, seat.id, admin, SeatSource.MANUAL, seat.version)
		assertThat(released.source to released.state).isEqualTo(SeatSource.MANUAL to SeatState.RELEASED)
	}

	@Test
	fun `중복 실행 방지 — 선점 중인 연결은 다시 잡히지 않고 기한이 지나면 다른 실행이 가져가며 앞선 실행은 아무것도 쓰지 못한다`() {
		connect(cursor)
		val first = run(cursor, worker = "worker-a", lease = Duration.ofMinutes(5))
		assertThat(ledger.startRun(tenant, first.connectionId, "worker-b", Duration.ofMinutes(5))).isNull()

		clock.now = clock.now.plus(Duration.ofMinutes(6))
		val second = ledger.startRun(tenant, first.connectionId, "worker-b", Duration.ofMinutes(5))!!
		assertThat(jdbc.sql("SELECT status || ':' || error FROM enrollment.seat_sync_runs WHERE id = :id").param("id", first.id).query(String::class.java).single())
			.isEqualTo("failed:abandoned")
		assertThat(ledger.applyListing(first, listOf(VendorSeat("octo@seat.example.test")))).isEqualTo(SeatLedger.SyncResult.Lost)
		assertThat(ledger.failRun(first, "vendor_unavailable")).isFalse()
		assertThat(ledger.seats(tenant, cursor)).isEmpty()
		assertThat(ledger.applyListing(second, listOf(VendorSeat("octo@seat.example.test")))).isEqualTo(SeatLedger.SyncResult.Applied(1, 1))
		// 다른 조직의 ID 로는 잡히지 않는다.
		assertThat(ledger.startRun(tenant(), second.connectionId, "worker-c", Duration.ofMinutes(5))).isNull()
	}

	@Test
	fun `연결을 지우는 사이 끝난 실행은 원장을 바꾸지 않는다`() {
		val connection = connect(cursor)
		val sync = run(cursor)
		connections.delete(tenant, admin, cursor, connection.version)
		assertThat(ledger.applyListing(sync, listOf(VendorSeat("octo@seat.example.test")))).isEqualTo(SeatLedger.SyncResult.Lost)
		assertThat(ledger.seats(tenant, cursor)).isEmpty()
		assertThat(jdbc.sql("SELECT error FROM enrollment.seat_sync_runs WHERE id = :id").param("id", sync.id).query(String::class.java).single()).isEqualTo("claim_lost")
	}

	private fun operation(id: UUID) = OperationStore(jdbc, manager, clock).find(tenant, id)!!

	@Test
	fun `동기화 요청 — 걸어 둔 요청은 주기와 무관하게 다음 실행이 가져가 결과를 작업에 옮기고, 끝나기 전의 요청은 쌓지 않는다`() {
		connect(cursor)
		ledger.applyListing(run(cursor), listOf(VendorSeat("octo@seat.example.test")))
		val interval = Duration.ofHours(1)
		assertThat(ledger.dueConnections(interval)).describedAs("방금 동기화했다").doesNotContain(tenant to connections.find(tenant, cursor)!!.id)

		val requested = ledger.requestSync(tenant, admin, cursor)
		assertThat(listOf(requested.kind, requested.status)).containsExactly(OperationKind.SEAT_SYNC, OperationStatus.PENDING)
		assertThat(requested.targets.map { it.targetId }).containsExactly(connections.find(tenant, cursor)!!.id.toString())
		assertThat(ledger.requestSync(tenant, admin, cursor).id).describedAs("끝나지 않은 요청을 돌려준다").isEqualTo(requested.id)
		assertThat(ledger.dueConnections(interval).first()).isEqualTo(tenant to connections.find(tenant, cursor)!!.id)

		val sync = run(cursor)
		assertThat(sync.operationId).isEqualTo(requested.id)
		assertThat(operation(requested.id).status).isEqualTo(OperationStatus.RUNNING)
		assertThat(jdbc.sql("SELECT trigger FROM enrollment.seat_sync_runs WHERE id = :id").param("id", sync.id).query(String::class.java).single()).isEqualTo("request")
		ledger.applyListing(sync, listOf(VendorSeat("octo@seat.example.test"), VendorSeat("hubot@seat.example.test")))
		assertThat(operation(requested.id).status).isEqualTo(OperationStatus.SUCCEEDED)
		assertThat(ledger.dueConnections(interval)).doesNotContain(tenant to sync.connectionId)

		// 끝난 요청 뒤에는 새 요청이다. 실패하면 사유가 작업에 남는다.
		val second = ledger.requestSync(tenant, admin, cursor)
		assertThat(second.id).isNotEqualTo(requested.id)
		ledger.failRun(run(cursor), "invalid_credentials")
		assertThat(operation(second.id).let { it.status to it.targets.single().reason }).isEqualTo(OperationStatus.FAILED to "invalid_credentials")
		clock.now = clock.now.plus(interval).plusSeconds(1)
		assertThat(ledger.dueConnections(interval)).contains(tenant to sync.connectionId)
	}

	@Test
	fun `동기화 요청 — 선점을 잃은 실행의 요청은 다음 실행이 이어받고, 지운 연결의 요청은 connection_removed 로 끝난다`() {
		connect(cursor)
		val requested = ledger.requestSync(tenant, admin, cursor)
		val first = run(cursor, worker = "worker-a", lease = Duration.ofMinutes(5))
		clock.now = clock.now.plus(Duration.ofMinutes(6))
		val second = run(cursor, worker = "worker-b")
		assertThat(second.operationId).isEqualTo(requested.id)
		assertThat(ledger.applyListing(first, listOf(VendorSeat("octo@seat.example.test")))).isEqualTo(SeatLedger.SyncResult.Lost)
		assertThat(operation(requested.id).status).isEqualTo(OperationStatus.RUNNING)
		ledger.applyListing(second, listOf(VendorSeat("octo@seat.example.test")))
		assertThat(operation(requested.id).status).isEqualTo(OperationStatus.SUCCEEDED)

		val pending = ledger.requestSync(tenant, admin, cursor)
		connections.delete(tenant, admin, cursor, connections.find(tenant, cursor)!!.version)
		assertThat(ledger.abandonRemovedRequests()).isEqualTo(1)
		assertThat(operation(pending.id).let { it.status to it.targets.single().reason }).isEqualTo(OperationStatus.FAILED to "connection_removed")
		assertThat(ledger.abandonRemovedRequests()).isZero()

		// 실행 중에 연결을 지우면 그 실행의 요청도 끝난다.
		connect(cursor)
		val third = ledger.requestSync(tenant, admin, cursor)
		val running = run(cursor)
		connections.delete(tenant, admin, cursor, connections.find(tenant, cursor)!!.version)
		assertThat(ledger.applyListing(running, listOf(VendorSeat("octo@seat.example.test")))).isEqualTo(SeatLedger.SyncResult.Lost)
		assertThat(operation(third.id).let { it.status to it.targets.single().reason }).isEqualTo(OperationStatus.FAILED to "connection_removed")
	}

	@Test
	fun `동기화 요청 — 연결이 없거나 다른 조직·구성원(member)이면 걸지 못한다`() {
		assertThat(code { ledger.requestSync(tenant, admin, cursor) }).isEqualTo("not_found" to 404)
		connect(cursor)
		assertThat(code { ledger.requestSync(tenant, dana, cursor) }).isEqualTo("forbidden" to 403)
		val other = tenant()
		assertThat(code { ledger.requestSync(other, member(other, "admin@other.example.test", "admin"), cursor) }).isEqualTo("not_found" to 404)
	}
}
