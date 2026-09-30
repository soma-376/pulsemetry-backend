package com.team376.pulsemetry.persistence.enrollment.seat

import com.team376.pulsemetry.connector.vendor.AccountKind
import com.team376.pulsemetry.connector.vendor.ConnectorDescriptors
import com.team376.pulsemetry.connector.vendor.VendorSeat
import com.team376.pulsemetry.connector.vendor.VendorSeatState
import com.team376.pulsemetry.persistence.enrollment.management.ManagementException
import com.team376.pulsemetry.persistence.enrollment.operation.Operation
import com.team376.pulsemetry.persistence.enrollment.operation.OperationKind
import com.team376.pulsemetry.persistence.enrollment.operation.OperationStatus
import com.team376.pulsemetry.persistence.enrollment.operation.OperationStore
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 좌석 원장 (ADR 0048). 좌석은 (등록 제품, 벤더 계정 키) 하나이고 판마다 이력 한 행을 남긴다.
 *
 * - 수동·CSV 의 배정·해제는 활성 연결이 없는 제품에서만 된다(§3 표의 1·2행). 연결이 있으면 `409 connector_managed`.
 * - 보정(구성원 연결·계약 등급·메모)은 권위와 무관하게 된다. 동기화는 관리자가 정한 구성원 연결·계약 등급·메모를 덮지 않는다.
 * - 동기화는 **목록 전체**로 원장을 맞춘다(§3 표의 3행) — 목록에 없는 보유 좌석은 수동 원천이어도 해제된다. 실패한 동기화는 원장을 바꾸지 않는다(4행).
 * - 모든 쓰기는 등록 제품 행을 잠가 제품 단위로 직렬화한다. 구매 수량으로 좌석을 만들지 않는다.
 */
class SeatLedger(private val jdbc: JdbcClient, manager: PlatformTransactionManager, private val clock: Clock) {
	private val tx = TransactionTemplate(manager)
	private val operations = OperationStore(jdbc, manager, clock)

	/** 구성원 연결의 선택 (ADR 0048 §4). */
	sealed interface MemberChoice {
		/** 이메일 일치 규칙에 맡긴다. 새로 배정할 때는 관리자가 정한 기존 연결을 둔다. */
		data object Automatic : MemberChoice
		/** 관리자가 이 구성원으로 정한다. */
		data class Member(val memberId: UUID) : MemberChoice
		/** 관리자가 '잇지 않음'으로 정한다. */
		data object Unlinked : MemberChoice
	}

	/** 보정에서 바꿀 값. 인자가 null 이면 그대로 둔다. */
	data class Change<T>(val value: T)

	data class Seat(
		val id: UUID,
		val tenantId: UUID,
		val vendorId: String,
		val account: String,
		val accountKind: AccountKind,
		val vendorAccountRef: String?,
		val accountEmail: String?,
		val state: SeatState,
		val source: SeatSource,
		val memberId: UUID?,
		val memberLink: MemberLink?,
		val tierId: String?,
		val vendorTier: String?,
		val assignedAt: Instant,
		val releaseEffectiveOn: LocalDate?,
		val releasedAt: Instant?,
		val vendorLastActivityAt: Instant?,
		val note: String?,
		val version: Long,
		val updatedAt: Instant,
	) {
		/** 판을 올리는 값만 비교한다 — 벤더 활동 시각·갱신 시각·판은 뺀다. */
		internal fun sameLedgerValues(other: Seat) = copy(vendorLastActivityAt = null, version = 0, updatedAt = Instant.EPOCH) ==
			other.copy(vendorLastActivityAt = null, version = 0, updatedAt = Instant.EPOCH)
	}

	data class Event(
		val version: Long,
		val state: SeatState,
		val source: SeatSource,
		val memberId: UUID?,
		val memberLink: MemberLink?,
		val tierId: String?,
		val vendorTier: String?,
		val releaseEffectiveOn: LocalDate?,
		val note: String?,
		val actorId: UUID?,
		val syncRunId: UUID?,
		val recordedAt: Instant,
	)

	/** 선점한 동기화 실행 하나. [operationId] 는 이 실행이 끝내는 동기화 요청 작업이다(없으면 주기 실행). */
	data class SyncRun(val id: UUID, val tenantId: UUID, val connectionId: UUID, val vendorId: String, val worker: String, val operationId: UUID? = null)

	sealed interface SyncResult {
		/** 목록을 반영했다. [changed] 는 판이 오른(또는 새로 생긴) 좌석 수다. */
		data class Applied(val listed: Int, val changed: Int) : SyncResult
		/** 목록이 원장에 넣을 수 없는 모양이다. 실행은 실패로 닫혔고 원장은 그대로다. */
		data class Rejected(val error: String) : SyncResult
		/** 선점을 잃었다(기한 만료로 다른 실행이 가져갔거나 연결이 지워졌다). 아무것도 쓰지 않았다. */
		data object Lost : SyncResult
	}

	// ---- 수동·CSV (ADR 0048 §3의 1·2행) ----

	/** 새 좌석을 배정하거나 해제된 좌석을 다시 배정한다. 이미 보유 중인 좌석은 `seat_already_held` 다(바꾸려면 [correct]). */
	fun assign(tenant: UUID, vendorId: String, actor: UUID, source: SeatSource, account: String, member: MemberChoice = MemberChoice.Automatic,
		tierId: String? = null, note: String? = null, expectedVersion: Long? = null): Seat = write {
		requireManual(source)
		RegisteredProducts.requireManager(jdbc, tenant, actor)
		val product = RegisteredProducts.lock(jdbc, tenant, vendorId)
		requireNoConnection(tenant, vendorId)
		val kind = ConnectorDescriptors.accountKind(product.kind)
		val key = kind.normalize(account) ?: fail("invalid_request", 400, "account")
		checkTier(product, tierId)
		checkNote(note)
		val now = now()
		val existing = seatByAccount(tenant, vendorId, key)
		val email = if (kind == AccountKind.EMAIL) key else existing?.accountEmail
		if (existing == null) {
			if (expectedVersion != null && expectedVersion != 0L) fail("version_conflict", 409, "expectedVersion")
			val (memberId, link) = resolve(tenant, member, email, null)
			val seat = Seat(UUID.randomUUID(), tenant, vendorId, key, kind, null, email, SeatState.ASSIGNED, source, memberId, link, tierId, null,
				now, null, null, null, note, 1, now)
			insert(seat)
			event(seat, actor = actor)
		} else {
			if (expectedVersion != existing.version) fail("version_conflict", 409, "expectedVersion")
			if (!SeatTransitions.allowed(source, existing.state, SeatState.ASSIGNED)) fail("seat_already_held", 409)
			val (memberId, link) = resolve(tenant, member, email, existing)
			val seat = existing.copy(state = SeatState.ASSIGNED, source = source, memberId = memberId, memberLink = link, tierId = tierId, note = note,
				assignedAt = now, releaseEffectiveOn = null, releasedAt = null, version = existing.version + 1, updatedAt = now)
			update(seat)
			event(seat, actor = actor)
		}
	}

	/** 배정된 좌석을 해제로 기록한다. 해제 예정·배정 대기는 벤더 제어의 몫이라 여기서 바꾸지 않는다(`seat_not_releasable`). */
	fun release(tenant: UUID, seatId: UUID, actor: UUID, source: SeatSource, expectedVersion: Long): Seat = write {
		requireManual(source)
		RegisteredProducts.requireManager(jdbc, tenant, actor)
		val vendorId = vendorOf(tenant, seatId)
		RegisteredProducts.lock(jdbc, tenant, vendorId)
		requireNoConnection(tenant, vendorId)
		val existing = seat(tenant, seatId, lock = true)!!
		if (existing.version != expectedVersion) fail("version_conflict", 409, "expectedVersion")
		if (!SeatTransitions.allowed(source, existing.state, SeatState.RELEASED)) fail("seat_not_releasable", 409)
		val now = now()
		val seat = existing.copy(state = SeatState.RELEASED, source = source, releasedAt = now, releaseEffectiveOn = null, version = existing.version + 1, updatedAt = now)
		update(seat)
		event(seat, actor = actor)
	}

	/**
	 * 보정 — 구성원 연결·계약 등급·메모. 권위와 무관하게 되고 상태·원천은 바꾸지 않는다. 바뀐 것이 없으면 판을 올리지 않는다.
	 * [member] 의 [MemberChoice.Automatic] 은 관리자 연결을 거두고 이메일 일치 규칙으로 돌려놓는다.
	 */
	fun correct(tenant: UUID, seatId: UUID, actor: UUID, expectedVersion: Long, member: MemberChoice? = null,
		tierId: Change<String?>? = null, note: Change<String?>? = null): Seat = write {
		if (member == null && tierId == null && note == null) fail("invalid_request", 400)
		RegisteredProducts.requireManager(jdbc, tenant, actor)
		val vendorId = vendorOf(tenant, seatId)
		val product = RegisteredProducts.lock(jdbc, tenant, vendorId)
		val existing = seat(tenant, seatId, lock = true)!!
		if (existing.version != expectedVersion) fail("version_conflict", 409, "expectedVersion")
		tierId?.let { checkTier(product, it.value) }
		note?.let { checkNote(it.value) }
		val (memberId, link) = when (member) {
			null -> existing.memberId to existing.memberLink
			MemberChoice.Automatic -> emailMatch(tenant, existing.accountEmail)
			else -> resolve(tenant, member, existing.accountEmail, existing)
		}
		val now = now()
		val next = existing.copy(memberId = memberId, memberLink = link, tierId = if (tierId != null) tierId.value else existing.tierId,
			note = if (note != null) note.value else existing.note)
		if (next.sameLedgerValues(existing)) return@write existing
		val seat = next.copy(version = existing.version + 1, updatedAt = now)
		update(seat)
		event(seat, actor = actor)
	}

	// ---- 커넥터 동기화 (ADR 0048 §3의 3·4행, §7) ----

	/**
	 * 연결의 동기화를 선점한다. 활성 연결이고 선점이 없거나 기한이 지났을 때만 된다 — 아니면 null(다른 실행이 돌고 있다).
	 * 기한이 지난 진행 중 실행은 `abandoned` 로 닫고, 그 실행이 끝내려던 요청 작업을 이어받는다. 연결에 걸린 요청이 있으면 가져가 시작한다.
	 * 계기는 요청 작업이 있으면 `request`, 없으면 `schedule` 이다.
	 */
	fun startRun(tenant: UUID, connectionId: UUID, worker: String, lease: Duration): SyncRun? = write {
		require(!lease.isNegative && !lease.isZero) { "선점 기한은 양수여야 한다" }
		val now = now()
		val (vendorId, requested) = jdbc.sql("""SELECT vendor_id, sync_requested_operation_id FROM enrollment.vendor_connections WHERE id = :id AND tenant_id = :tenant
				AND deleted_at IS NULL AND (sync_claimed_until IS NULL OR sync_claimed_until <= :now) FOR UPDATE SKIP LOCKED""")
			.param("id", connectionId).param("tenant", tenant).param("now", Timestamp.from(now))
			.query { rs, _ -> rs.getString(1) to rs.getObject(2, UUID::class.java) }.optional().orElse(null) ?: return@write null
		val carried = jdbc.sql("SELECT operation_id FROM enrollment.seat_sync_runs WHERE connection_id = :id AND status = 'running' AND operation_id IS NOT NULL")
			.param("id", connectionId).query { rs, _ -> rs.getObject(1, UUID::class.java) }.optional().orElse(null)
		jdbc.sql("UPDATE enrollment.seat_sync_runs SET status = 'failed', error = 'abandoned', finished_at = :now WHERE connection_id = :id AND status = 'running'")
			.param("now", Timestamp.from(now)).param("id", connectionId).update()
		// 이어받은 요청이 있으면 걸린 요청은 다음 실행 몫으로 남긴다 — 실행 하나가 요청 하나를 끝낸다.
		val operation = carried ?: requested
		if (carried == null && requested != null) {
			jdbc.sql("UPDATE enrollment.vendor_connections SET sync_requested_operation_id = NULL WHERE id = :id").param("id", connectionId).update()
			if (operations.find(tenant, requested)?.status == OperationStatus.PENDING) operations.start(tenant, requested)
		}
		jdbc.sql("UPDATE enrollment.vendor_connections SET sync_claimed_by = :worker, sync_claimed_until = :until WHERE id = :id")
			.param("worker", worker).param("until", Timestamp.from(now.plus(lease))).param("id", connectionId).update()
		val run = SyncRun(UUID.randomUUID(), tenant, connectionId, vendorId, worker, operation)
		jdbc.sql("""INSERT INTO enrollment.seat_sync_runs (id, tenant_id, connection_id, trigger, worker, started_at, status, operation_id)
				VALUES (:id, :tenant, :connection, :trigger, :worker, :now, 'running', :operation)""")
			.param("id", run.id).param("tenant", tenant).param("connection", connectionId).param("trigger", if (operation != null) "request" else "schedule")
			.param("worker", worker).param("now", Timestamp.from(now)).param("operation", operation, Types.OTHER).update()
		run
	}

	/**
	 * 동기화할 차례인 연결(조직, 연결 ID). 선점되지 않았고, 요청이 걸려 있거나 마지막 시도(성공·실패)가 [interval] 보다 오래됐거나 시도한 적이 없는 것.
	 * 요청이 걸린 연결이 먼저, 그다음은 오래된 순이다.
	 */
	fun dueConnections(interval: Duration): List<Pair<UUID, UUID>> {
		val now = now()
		return jdbc.sql("""SELECT tenant_id, id FROM enrollment.vendor_connections
			WHERE deleted_at IS NULL AND (sync_claimed_until IS NULL OR sync_claimed_until <= :now)
			  AND (sync_requested_operation_id IS NOT NULL OR greatest(last_sync_succeeded_at, last_sync_failed_at) IS NULL
			       OR greatest(last_sync_succeeded_at, last_sync_failed_at) <= :due)
			ORDER BY sync_requested_operation_id IS NULL, greatest(last_sync_succeeded_at, last_sync_failed_at) NULLS FIRST, id""")
			.param("now", Timestamp.from(now)).param("due", Timestamp.from(now.minus(interval)))
			.query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getObject(2, UUID::class.java) }.list()
	}

	/**
	 * 관리자의 "지금 동기화" (ADR 0048 §7). 활성 연결에 요청 작업(`seat_sync`, 대상 = 연결 ID)을 걸어 둔다 — 다음 주기 실행이 가져간다.
	 * 아직 끝나지 않은 요청이 걸려 있으면 그 작업을 돌려준다(같은 연결에 요청을 쌓지 않는다). 연결이 없으면 404.
	 */
	fun requestSync(tenant: UUID, actor: UUID, vendorId: String): Operation = write {
		RegisteredProducts.requireManager(jdbc, tenant, actor)
		RegisteredProducts.lock(jdbc, tenant, vendorId)
		val (connectionId, requested) = jdbc.sql("""SELECT id, sync_requested_operation_id FROM enrollment.vendor_connections
				WHERE tenant_id = :tenant AND vendor_id = :vendor AND deleted_at IS NULL FOR UPDATE""")
			.param("tenant", tenant).param("vendor", vendorId).query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getObject(2, UUID::class.java) }
			.optional().orElse(null) ?: fail("not_found", 404, "vendorId")
		requested?.let { operations.find(tenant, it) }?.takeUnless { it.status.closed }?.let { return@write it }
		val operation = operations.create(tenant, OperationKind.SEAT_SYNC, actor, listOf(connectionId.toString()))
		jdbc.sql("UPDATE enrollment.vendor_connections SET sync_requested_operation_id = :operation WHERE id = :id")
			.param("operation", operation.id).param("id", connectionId).update()
		operation
	}

	/** 지운 연결에 걸려 있던 요청을 `connection_removed` 로 끝낸다. 주기 실행이 매번 부른다. */
	fun abandonRemovedRequests(): Int = write {
		val stale = jdbc.sql("SELECT id, tenant_id, sync_requested_operation_id FROM enrollment.vendor_connections WHERE deleted_at IS NOT NULL AND sync_requested_operation_id IS NOT NULL FOR UPDATE")
			.query { rs, _ -> Triple(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getObject(3, UUID::class.java)) }.list()
		stale.forEach { (connection, tenant, operation) ->
			if (operations.find(tenant, operation)?.status?.closed == false) operations.abort(tenant, operation, "connection_removed")
			jdbc.sql("UPDATE enrollment.vendor_connections SET sync_requested_operation_id = NULL WHERE id = :id").param("id", connection).update()
		}
		stale.size
	}

	/** 벤더 목록 **전체**로 원장을 맞춘다. 목록의 계정 키가 형식에 맞지 않거나 겹치면 반영하지 않고 실패로 닫는다. */
	fun applyListing(run: SyncRun, seats: List<VendorSeat>): SyncResult = write {
		val product = try { RegisteredProducts.lock(jdbc, run.tenantId, run.vendorId) } catch (_: ManagementException) { null }
		if (!ownsRun(run)) return@write SyncResult.Lost
		val now = now()
		if (product == null || !claimed(run)) {
			// 실행이 아직 제 것인데 선점이 없다 — 연결이 지워졌다(보관 포함). 요청 작업도 끝낸다.
			closeRun(run, "failed", "claim_lost", null, null, now)
			settle(run, "connection_removed")
			return@write SyncResult.Lost
		}
		val kind = ConnectorDescriptors.accountKind(product.kind)
		val keys = seats.map { kind.normalize(it.account) }
		if (keys.any { it == null } || keys.toSet().size != keys.size) {
			failConnection(run, "invalid_listing", now)
			return@write SyncResult.Rejected("invalid_listing")
		}
		val existing = jdbc.sql("SELECT * FROM enrollment.seat_assignments WHERE tenant_id = :tenant AND vendor_id = :vendor FOR UPDATE")
			.param("tenant", run.tenantId).param("vendor", run.vendorId).query { rs, _ -> seat(rs) }.list().associateBy { it.account }
		val members = memberEmails(run.tenantId)
		var changed = 0
		seats.zip(keys.map { it!! }).forEach { (listed, key) ->
			// 원장은 마이크로초까지 저장한다 — 벤더 시각을 그 정밀도로 맞춰야 같은 값을 변경으로 보지 않는다.
			val assignedAtVendor = listed.assignedAt?.truncatedTo(ChronoUnit.MICROS)
			val activity = listed.lastActivityAt?.truncatedTo(ChronoUnit.MICROS)
			val email = listed.email?.let(AccountKind.EMAIL::normalize) ?: key.takeIf { kind == AccountKind.EMAIL }
			val state = when (listed.state) {
				VendorSeatState.ASSIGNED -> SeatState.ASSIGNED
				VendorSeatState.PENDING_ASSIGNMENT -> SeatState.PENDING_ASSIGNMENT
				VendorSeatState.PENDING_RELEASE -> SeatState.PENDING_RELEASE
			}
			val row = existing[key]
			val (memberId, link) = if (row?.memberLink == MemberLink.ADMIN) row.memberId to row.memberLink else members.match(email)
			if (row == null) {
				val seat = Seat(UUID.randomUUID(), run.tenantId, run.vendorId, key, kind, listed.vendorAccountRef, email, state, SeatSource.CONNECTOR, memberId, link,
					null, listed.tier, assignedAtVendor ?: now, listed.releaseEffectiveOn, null, activity, null, 1, now)
				insert(seat)
				event(seat, syncRun = run.id)
				changed++
			} else {
				val assignedAt = when {
					!row.state.holds -> assignedAtVendor ?: now
					row.source != SeatSource.CONNECTOR && assignedAtVendor != null -> assignedAtVendor
					else -> row.assignedAt
				}
				val next = row.copy(vendorAccountRef = listed.vendorAccountRef, accountEmail = email, state = state, source = SeatSource.CONNECTOR, memberId = memberId,
					memberLink = link, vendorTier = listed.tier, assignedAt = assignedAt, releaseEffectiveOn = listed.releaseEffectiveOn, releasedAt = null,
					vendorLastActivityAt = activity)
				if (!next.sameLedgerValues(row)) {
					val seat = next.copy(version = row.version + 1, updatedAt = now)
					update(seat)
					event(seat, syncRun = run.id)
					changed++
				} else if (next.vendorLastActivityAt != row.vendorLastActivityAt) {
					// 활동 시각은 관측이다 — 판·이력 없이 갱신한다.
					jdbc.sql("UPDATE enrollment.seat_assignments SET vendor_last_activity_at = :at WHERE id = :id")
						.param("at", next.vendorLastActivityAt?.let(Timestamp::from), Types.TIMESTAMP).param("id", row.id).update()
				}
			}
		}
		val listedKeys = keys.toSet()
		existing.values.filter { it.account !in listedKeys && it.state.holds }.forEach { row ->
			val seat = row.copy(state = SeatState.RELEASED, source = SeatSource.CONNECTOR, releasedAt = now, releaseEffectiveOn = null, version = row.version + 1, updatedAt = now)
			update(seat)
			event(seat, syncRun = run.id)
			changed++
		}
		closeRun(run, "succeeded", null, seats.size, changed, now)
		jdbc.sql("""UPDATE enrollment.vendor_connections SET last_sync_succeeded_at = :now, last_sync_failed_at = NULL, last_sync_error = NULL,
				sync_claimed_by = NULL, sync_claimed_until = NULL WHERE id = :id""")
			.param("now", Timestamp.from(now)).param("id", run.connectionId).update()
		settle(run, null)
		SyncResult.Applied(seats.size, changed)
	}

	/** 동기화 실패를 남긴다. 원장은 바꾸지 않는다(§3 표의 4행). 선점을 잃었으면 false. */
	fun failRun(run: SyncRun, error: String): Boolean = write {
		require(ERROR.matches(error)) { "오류 코드는 [a-z_]{1,64} 여야 한다" }
		if (!ownsRun(run)) return@write false
		val now = now()
		if (!claimed(run)) {
			closeRun(run, "failed", "claim_lost", null, null, now)
			settle(run, "connection_removed")
			return@write false
		}
		failConnection(run, error, now)
		true
	}

	/** 실행이 끝내는 요청 작업에 결과를 옮긴다. [error] 가 null 이면 성공이다. */
	private fun settle(run: SyncRun, error: String?) {
		val operation = run.operationId?.let { operations.find(run.tenantId, it) } ?: return
		if (operation.status != OperationStatus.RUNNING) return
		if (error == null) operations.succeed(run.tenantId, operation.id, run.connectionId.toString())
		else operations.fail(run.tenantId, operation.id, run.connectionId.toString(), error)
	}

	// ---- 읽기 ----

	fun seat(tenant: UUID, seatId: UUID): Seat? = seat(tenant, seatId, lock = false)

	fun seats(tenant: UUID, vendorId: String): List<Seat> =
		jdbc.sql("SELECT * FROM enrollment.seat_assignments WHERE tenant_id = :tenant AND vendor_id = :vendor ORDER BY account")
			.param("tenant", tenant).param("vendor", vendorId).query { rs, _ -> seat(rs) }.list()

	fun history(tenant: UUID, seatId: UUID): List<Event> = jdbc.sql("""SELECT * FROM enrollment.seat_assignment_events WHERE tenant_id = :tenant AND seat_assignment_id = :id
			ORDER BY version""")
		.param("tenant", tenant).param("id", seatId).query { rs, _ ->
			Event(rs.getLong("version"), SeatState.of(rs.getString("state")), SeatSource.of(rs.getString("source")), rs.getObject("member_id", UUID::class.java),
				MemberLink.of(rs.getString("member_link")), rs.getString("tier_id"), rs.getString("vendor_tier"), rs.getObject("release_effective_on", LocalDate::class.java),
				rs.getString("note"), rs.getObject("actor_id", UUID::class.java), rs.getObject("sync_run_id", UUID::class.java), rs.getTimestamp("recorded_at").toInstant())
		}.list()

	// ---- 내부 ----

	private fun requireManual(source: SeatSource) = require(source == SeatSource.MANUAL || source == SeatSource.CSV) { "수동 기록의 원천은 manual·csv 다" }

	private fun requireNoConnection(tenant: UUID, vendorId: String) {
		val connected = jdbc.sql("SELECT EXISTS (SELECT 1 FROM enrollment.vendor_connections WHERE tenant_id = :tenant AND vendor_id = :vendor AND deleted_at IS NULL)")
			.param("tenant", tenant).param("vendor", vendorId).query(Boolean::class.java).single()
		if (connected) fail("connector_managed", 409)
	}

	private fun checkTier(product: RegisteredProduct, tierId: String?) {
		if (tierId != null && tierId !in product.tierIds) fail("invalid_tier", 422, "tierId")
	}

	private fun checkNote(note: String?) {
		if (note != null && (note.isBlank() || note.length > 1000)) fail("invalid_request", 400, "note")
	}

	private fun vendorOf(tenant: UUID, seatId: UUID): String = jdbc.sql("SELECT vendor_id FROM enrollment.seat_assignments WHERE tenant_id = :tenant AND id = :id")
		.param("tenant", tenant).param("id", seatId).query(String::class.java).optional().orElse(null) ?: fail("not_found", 404, "seatAssignmentId")

	/** 관리자 선택이면 그대로(같은 조직의 구성원인지 확인), 자동이면 기존 관리자 연결을 두거나 이메일 일치 규칙이다. */
	private fun resolve(tenant: UUID, choice: MemberChoice, email: String?, existing: Seat?): Pair<UUID?, MemberLink?> = when (choice) {
		is MemberChoice.Member -> {
			val found = jdbc.sql("SELECT EXISTS (SELECT 1 FROM enrollment.members WHERE tenant_id = :tenant AND id = :id)")
				.param("tenant", tenant).param("id", choice.memberId).query(Boolean::class.java).single()
			if (!found) fail("not_found", 404, "memberId")
			choice.memberId to MemberLink.ADMIN
		}
		MemberChoice.Unlinked -> null to MemberLink.ADMIN
		MemberChoice.Automatic -> if (existing?.memberLink == MemberLink.ADMIN) existing.memberId to MemberLink.ADMIN else emailMatch(tenant, email)
	}

	private fun emailMatch(tenant: UUID, email: String?): Pair<UUID?, MemberLink?> = memberEmails(tenant).match(email)

	/** 조직 구성원의 이메일 색인. 같은 이메일이 둘 이상이면 잇지 않는다. */
	private class MemberEmails(private val ids: Map<String, List<UUID>>) {
		fun match(email: String?): Pair<UUID?, MemberLink?> = ids[email]?.singleOrNull()?.let { it to MemberLink.EMAIL_MATCH } ?: (null to null)
	}

	private fun memberEmails(tenant: UUID) = MemberEmails(
		jdbc.sql("SELECT id, lower(email) AS email FROM enrollment.members WHERE tenant_id = :tenant").param("tenant", tenant)
			.query { rs, _ -> rs.getString("email") to rs.getObject("id", UUID::class.java) }.list().groupBy({ it.first }, { it.second }),
	)

	private fun seatByAccount(tenant: UUID, vendorId: String, account: String): Seat? =
		jdbc.sql("SELECT * FROM enrollment.seat_assignments WHERE tenant_id = :tenant AND vendor_id = :vendor AND account = :account FOR UPDATE")
			.param("tenant", tenant).param("vendor", vendorId).param("account", account).query { rs, _ -> seat(rs) }.optional().orElse(null)

	private fun seat(tenant: UUID, seatId: UUID, lock: Boolean): Seat? =
		jdbc.sql("SELECT * FROM enrollment.seat_assignments WHERE tenant_id = :tenant AND id = :id" + if (lock) " FOR UPDATE" else "")
			.param("tenant", tenant).param("id", seatId).query { rs, _ -> seat(rs) }.optional().orElse(null)

	private fun seat(rs: ResultSet) = Seat(
		id = rs.getObject("id", UUID::class.java),
		tenantId = rs.getObject("tenant_id", UUID::class.java),
		vendorId = rs.getString("vendor_id"),
		account = rs.getString("account"),
		accountKind = AccountKind.entries.first { it.wire == rs.getString("account_kind") },
		vendorAccountRef = rs.getString("vendor_account_ref"),
		accountEmail = rs.getString("account_email"),
		state = SeatState.of(rs.getString("state")),
		source = SeatSource.of(rs.getString("source")),
		memberId = rs.getObject("member_id", UUID::class.java),
		memberLink = MemberLink.of(rs.getString("member_link")),
		tierId = rs.getString("tier_id"),
		vendorTier = rs.getString("vendor_tier"),
		assignedAt = rs.getTimestamp("assigned_at").toInstant(),
		releaseEffectiveOn = rs.getObject("release_effective_on", LocalDate::class.java),
		releasedAt = rs.getTimestamp("released_at")?.toInstant(),
		vendorLastActivityAt = rs.getTimestamp("vendor_last_activity_at")?.toInstant(),
		note = rs.getString("note"),
		version = rs.getLong("version"),
		updatedAt = rs.getTimestamp("updated_at").toInstant(),
	)

	private fun insert(seat: Seat) {
		jdbc.sql("""INSERT INTO enrollment.seat_assignments (id, tenant_id, vendor_id, account, account_kind, vendor_account_ref, account_email, state, source, member_id,
				member_link, tier_id, vendor_tier, assigned_at, release_effective_on, released_at, vendor_last_activity_at, note, version, updated_at)
			VALUES (:id, :tenant, :vendor, :account, :kind, :ref, :email, :state, :source, :member,
				:link, :tier, :vendorTier, :assignedAt, :effective, :releasedAt, :activity, :note, :version, :updatedAt)""")
			.let { bind(it, seat) }.param("tenant", seat.tenantId).param("vendor", seat.vendorId).param("account", seat.account).param("kind", seat.accountKind.wire).update()
	}

	private fun update(seat: Seat) {
		jdbc.sql("""UPDATE enrollment.seat_assignments SET vendor_account_ref = :ref, account_email = :email, state = :state,
				source = :source, member_id = :member, member_link = :link, tier_id = :tier,
				vendor_tier = :vendorTier, assigned_at = :assignedAt, release_effective_on = :effective, released_at = :releasedAt, vendor_last_activity_at = :activity,
				note = :note, version = :version, updated_at = :updatedAt
			WHERE id = :id""").let { bind(it, seat) }.update()
	}

	private fun bind(statement: JdbcClient.StatementSpec, seat: Seat): JdbcClient.StatementSpec = statement
		.param("id", seat.id).param("ref", seat.vendorAccountRef, Types.VARCHAR).param("email", seat.accountEmail, Types.VARCHAR)
		.param("state", seat.state.wire).param("source", seat.source.wire).param("member", seat.memberId, Types.OTHER)
		.param("link", seat.memberLink?.wire, Types.VARCHAR).param("tier", seat.tierId, Types.VARCHAR).param("vendorTier", seat.vendorTier, Types.VARCHAR)
		.param("assignedAt", Timestamp.from(seat.assignedAt)).param("effective", seat.releaseEffectiveOn, Types.DATE)
		.param("releasedAt", seat.releasedAt?.let(Timestamp::from), Types.TIMESTAMP)
		.param("activity", seat.vendorLastActivityAt?.let(Timestamp::from), Types.TIMESTAMP)
		.param("note", seat.note, Types.VARCHAR).param("version", seat.version).param("updatedAt", Timestamp.from(seat.updatedAt))

	/** 판마다 한 행 — 바뀐 뒤의 값과 행위자 하나. */
	private fun event(seat: Seat, actor: UUID? = null, syncRun: UUID? = null): Seat {
		jdbc.sql("""INSERT INTO enrollment.seat_assignment_events (seat_assignment_id, version, tenant_id, state, source, member_id, member_link, tier_id, vendor_tier,
				release_effective_on, note, actor_id, sync_run_id, recorded_at)
			VALUES (:id, :version, :tenant, :state, :source, :member, :link,
				:tier, :vendorTier, :effective, :note, :actor, :run, :at)""")
			.param("id", seat.id).param("version", seat.version).param("tenant", seat.tenantId).param("state", seat.state.wire).param("source", seat.source.wire)
			.param("member", seat.memberId, Types.OTHER).param("link", seat.memberLink?.wire, Types.VARCHAR).param("tier", seat.tierId, Types.VARCHAR)
			.param("vendorTier", seat.vendorTier, Types.VARCHAR).param("effective", seat.releaseEffectiveOn, Types.DATE).param("note", seat.note, Types.VARCHAR)
			.param("actor", actor, Types.OTHER).param("run", syncRun, Types.OTHER).param("at", Timestamp.from(seat.updatedAt)).update()
		return seat
	}

	/** 실행이 아직 진행 중인가(다른 실행이 `abandoned` 로 닫지 않았는가). 실행 행을 잠근다. */
	private fun ownsRun(run: SyncRun): Boolean = jdbc.sql("SELECT status FROM enrollment.seat_sync_runs WHERE id = :id FOR UPDATE")
		.param("id", run.id).query(String::class.java).optional().orElse(null) == "running"

	/** 연결이 살아 있고 이 실행의 선점이 그대로인가. 연결 행을 잠근다. */
	private fun claimed(run: SyncRun): Boolean = jdbc.sql("SELECT sync_claimed_by FROM enrollment.vendor_connections WHERE id = :id AND deleted_at IS NULL FOR UPDATE")
		.param("id", run.connectionId).query { rs, _ -> rs.getString(1) }.optional().orElse(null) == run.worker

	private fun failConnection(run: SyncRun, error: String, now: Instant) {
		closeRun(run, "failed", error, null, null, now)
		settle(run, error)
		jdbc.sql("""UPDATE enrollment.vendor_connections SET last_sync_failed_at = :now, last_sync_error = :error, sync_claimed_by = NULL, sync_claimed_until = NULL
			WHERE id = :id""").param("now", Timestamp.from(now)).param("error", error).param("id", run.connectionId).update()
	}

	private fun closeRun(run: SyncRun, status: String, error: String?, listed: Int?, changed: Int?, now: Instant) {
		jdbc.sql("UPDATE enrollment.seat_sync_runs SET status = :status, error = :error, listed_seats = :listed, changed_seats = :changed, finished_at = :now WHERE id = :id")
			.param("status", status).param("error", error, Types.VARCHAR).param("listed", listed, Types.INTEGER).param("changed", changed, Types.INTEGER)
			.param("now", Timestamp.from(now)).param("id", run.id).update()
	}

	private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)
	@Suppress("UNCHECKED_CAST")
	private fun <T> write(block: () -> T): T = tx.execute { block() } as T
	private fun fail(code: String, status: Int, field: String? = null): Nothing = throw ManagementException(code, status, field)

	private companion object {
		val ERROR = Regex("[a-z_]{1,64}")
	}
}
