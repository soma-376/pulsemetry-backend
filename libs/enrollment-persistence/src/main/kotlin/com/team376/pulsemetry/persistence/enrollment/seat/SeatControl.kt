package com.team376.pulsemetry.persistence.enrollment.seat

import com.team376.pulsemetry.connector.vendor.Capability
import com.team376.pulsemetry.connector.vendor.ConnectorDescriptors
import com.team376.pulsemetry.connector.vendor.ControlResult
import com.team376.pulsemetry.connector.vendor.VendorAccount
import com.team376.pulsemetry.persistence.enrollment.management.ContractStatus
import com.team376.pulsemetry.persistence.enrollment.management.ManagementException
import com.team376.pulsemetry.persistence.enrollment.operation.Operation
import com.team376.pulsemetry.persistence.enrollment.operation.OperationError
import com.team376.pulsemetry.persistence.enrollment.operation.OperationException
import com.team376.pulsemetry.persistence.enrollment.operation.OperationKind
import com.team376.pulsemetry.persistence.enrollment.operation.OperationStore
import com.team376.pulsemetry.persistence.enrollment.operation.OperationTargetStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.sql.Timestamp
import java.sql.Types
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 좌석 회수·복원 (ADR 0049). 원장은 벤더 호출이 성공했거나 관리자가 조치를 확인했을 때만 바뀐다 — 요청·접수만으로 바꾸지 않는다.
 *
 * - **미리보기**는 좌석마다 판·상태·실행 방식을 다시 계산하고, 실행할 수 있는 대상을 요청자·판·방식에 묶어 [PREVIEW_TTL] 동안 둔다.
 * - **실행**은 미리보기 하나로 회수 작업(`seat_reclaim`)을 만든다. 그 사이 대상의 판·방식이 바뀌었으면 `409 preview_stale` 로 전부 거절한다.
 *   벤더 제어 대상은 대기로 남고 주기 실행([claim]·[complete]·[fail])이 커넥터를 부른다. 관리자 조치 대상은 조치 대기다.
 * - **확인**([confirm])은 관리자 조치 대기 대상만 끝낸다(확인자는 작업 대상에 남는다). **취소**([cancel])는 원장을 바꾸지 않고 대상을 실패로 끝낸다.
 *   조치 대기에는 기한이 없다 — 관리자가 확인하거나 취소할 때까지 남는다.
 * - **복원**은 회수에서 성공한 대상을 [RESTORE_WINDOW] 안에 되돌린다. 좌석이 그 사이 다시 보유됐으면 그 대상은 실패로 남는다.
 */
class SeatControl(
	private val jdbc: JdbcClient,
	manager: PlatformTransactionManager,
	private val clock: Clock,
	private val mapper: ObjectMapper,
	/** 이 배포가 커넥터 호출(주기 실행)을 조립했는가. 아니면 활성 연결이 있는 제품의 벤더 제어는 `connector_unavailable` 이다. */
	private val vendorControl: Boolean,
) {
	private val tx = TransactionTemplate(manager)
	private val operations = OperationStore(jdbc, manager, clock)
	private val ledger = SeatLedger(jdbc, manager, clock)

	enum class Action(val wire: String, val capability: Capability) {
		RELEASE("release", Capability.SEAT_RELEASE), RESTORE("restore", Capability.SEAT_RESTORE);

		companion object {
			fun of(wire: String): Action = entries.first { it.wire == wire }
		}
	}

	enum class Method(val wire: String) {
		VENDOR_CONTROL("vendor_control"), ADMIN_ACTION("admin_action");

		companion object {
			fun of(wire: String): Method = entries.first { it.wire == wire }
		}
	}

	/** 실행 방식의 판정. [reason] 이 있으면 실행할 수 없다. */
	data class Choice(val method: Method?, val connectionId: UUID?, val reason: String?) {
		companion object {
			fun rejected(reason: String) = Choice(null, null, reason)
		}
	}

	data class PreviewTarget(val seatAssignmentId: UUID, val vendorId: String, val version: Long, val method: Method, val connectionId: UUID?)
	data class Rejection(val seatAssignmentId: String, val reason: String)

	/**
	 * 미리보기. [estimatedMonthlySavingsUsd] 는 실행할 수 있는 좌석의 계약 등급 단가 합이다(유효한 계약에 그 좌석의 등급과 단가가 모두 있을 때만) —
	 * 구매 수량을 줄여야 실현되는 추정이고 그 시점은 모른다. [resultingUnallocatedSeats] 는 회수 뒤 계약 좌석 − 보유 좌석(제품마다 0 미만은 0)의 합이다.
	 */
	data class Preview(val id: UUID, val expiresAt: Instant, val targets: List<PreviewTarget>, val rejected: List<Rejection>,
		val estimatedMonthlySavingsUsd: BigDecimal?, val resultingUnallocatedSeats: Long?)

	/** 주기 실행이 선점한 벤더 제어 대상 하나. */
	data class Claim(val operationId: UUID, val seatAssignmentId: UUID, val tenantId: UUID, val action: Action, val connectionId: UUID,
		val account: VendorAccount, val worker: String)

	// ---- 명령 ----

	fun preview(tenant: UUID, actor: UUID, requested: List<Pair<String, Long>>): Preview = write {
		RegisteredProducts.requireManager(jdbc, tenant, actor)
		if (requested.size !in 1..MAX_SEATS || requested.map { it.first }.toSet().size != requested.size) fail("invalid_request", 400, "seats")
		val now = now()
		val connections = VendorConnections.active(jdbc, mapper, tenant).associateBy { it.vendorId }
		val eligible = mutableListOf<Pair<PreviewTarget, SeatLedger.Seat>>()
		val rejected = mutableListOf<Rejection>()
		requested.forEach { (raw, version) ->
			val seat = uuidOrNull(raw)?.let { ledger.seat(tenant, it) }
			// 보관한 등록 제품의 좌석은 없는 것과 같다.
			val product = seat?.let { runCatching { RegisteredProducts.find(jdbc, tenant, it.vendorId, lock = false) }.getOrNull() }
			val choice = when {
				seat == null || product == null -> Choice.rejected("not_found")
				seat.version != version -> Choice.rejected("version_conflict")
				else -> choose(Action.RELEASE, seat, product, connections[seat.vendorId]).let { if (it.reason == null && inProgress(seat.id)) Choice.rejected("control_in_progress") else it }
			}
			if (choice.reason != null || seat == null) rejected += Rejection(raw, choice.reason ?: "not_found")
			else eligible += PreviewTarget(seat.id, seat.vendorId, seat.version, choice.method!!, choice.connectionId) to seat
		}
		val id = UUID.randomUUID()
		val expires = now.plus(PREVIEW_TTL)
		jdbc.sql("""INSERT INTO enrollment.seat_reclaim_previews (id, tenant_id, requested_by, created_at, expires_at, targets)
				VALUES (:id, :tenant, :actor, :now, :expires, CAST(:targets AS jsonb))""")
			.param("id", id).param("tenant", tenant).param("actor", actor).param("now", Timestamp.from(now)).param("expires", Timestamp.from(expires))
			.param("targets", mapper.writeValueAsString(eligible.map { (t, _) ->
				mapOf("seatAssignmentId" to t.seatAssignmentId, "version" to t.version, "method" to t.method.wire, "connectionId" to t.connectionId) })).update()
		val (savings, unallocated) = impact(tenant, eligible.map { it.second }, now)
		Preview(id, expires, eligible.map { it.first }, rejected, savings, unallocated)
	}

	/** 미리보기 하나로 회수 작업을 만든다. 요청자가 다르거나 없는 미리보기는 404, 쓴 것은 `preview_used`, 기한이 지난 것은 `preview_expired`. */
	fun execute(tenant: UUID, actor: UUID, previewId: UUID): Operation = write {
		RegisteredProducts.requireManager(jdbc, tenant, actor)
		val now = now()
		val row = jdbc.sql("SELECT requested_by, expires_at, targets::text AS targets, operation_id FROM enrollment.seat_reclaim_previews WHERE id = :id AND tenant_id = :tenant FOR UPDATE")
			.param("id", previewId).param("tenant", tenant)
			.query { rs, _ -> PreviewRow(rs.getObject(1, UUID::class.java), rs.getTimestamp(2).toInstant(), rs.getString(3), rs.getObject(4, UUID::class.java)) }
			.optional().orElse(null)
		if (row == null || row.requestedBy != actor) fail("not_found", 404, "previewId")
		if (row.operationId != null) fail("preview_used", 409, "previewId")
		if (!now.isBefore(row.expiresAt)) fail("preview_expired", 409, "previewId")
		val targets = mapper.readTree(row.targets).toList().map {
			PreviewTarget(UUID.fromString(it.path("seatAssignmentId").asString()), "", it.path("version").asLong(), Method.of(it.path("method").asString()),
				it.path("connectionId").takeIf { c -> c.isString }?.asString()?.let(UUID::fromString))
		}
		if (targets.isEmpty()) fail("no_eligible_seats", 422, "previewId")
		val connections = VendorConnections.active(jdbc, mapper, tenant).associateBy { it.vendorId }
		targets.forEach { target ->
			val seat = ledger.seat(tenant, target.seatAssignmentId) ?: fail("preview_stale", 409, "previewId")
			val product = RegisteredProducts.lock(jdbc, tenant, seat.vendorId)
			val current = jdbc.sql("SELECT version FROM enrollment.seat_assignments WHERE id = :id FOR UPDATE").param("id", seat.id).query(Long::class.java).single()
			val choice = choose(Action.RELEASE, seat, product, connections[seat.vendorId])
			if (current != target.version || choice.method != target.method || choice.connectionId != target.connectionId || inProgress(seat.id)) {
				fail("preview_stale", 409, "previewId")
			}
		}
		val operation = operations.create(tenant, OperationKind.SEAT_RECLAIM, actor, targets.map { it.seatAssignmentId.toString() })
		operations.allowRestore(tenant, operation.id, now.plus(RESTORE_WINDOW))
		start(tenant, operation.id, Action.RELEASE, targets.map { Triple(it.seatAssignmentId, Choice(it.method, it.connectionId, null), null) }, now)
		jdbc.sql("UPDATE enrollment.seat_reclaim_previews SET operation_id = :operation WHERE id = :id").param("operation", operation.id).param("id", previewId).update()
		requireNotNull(operations.find(tenant, operation.id))
	}

	/**
	 * 회수를 되돌린다. 되돌릴 수 없는 회수(기한 밖·성공 없음·이미 되돌리는 중)나 되돌릴 대상이 하나도 없으면 `422 restore_not_available`
	 * (대상별 사유는 `details`). 일부만 되돌릴 수 있으면 나머지 대상은 사유와 함께 실패로 남는다.
	 */
	fun restore(tenant: UUID, actor: UUID, reclaimId: UUID): Operation = write {
		RegisteredProducts.requireManager(jdbc, tenant, actor)
		val now = now()
		val reclaim = operations.find(tenant, reclaimId)?.takeIf { it.kind == OperationKind.SEAT_RECLAIM } ?: fail("not_found", 404, "operationId")
		if (!reclaim.canRestore(now)) fail("restore_not_available", 422, "operationId")
		val connections = VendorConnections.active(jdbc, mapper, tenant).associateBy { it.vendorId }
		val choices = reclaim.targets.filter { it.status == OperationTargetStatus.SUCCEEDED }.map { target ->
			val seat = ledger.seat(tenant, UUID.fromString(target.targetId))
			val product = seat?.let { runCatching { RegisteredProducts.lock(jdbc, tenant, it.vendorId) }.getOrNull() }
			val choice = when {
				seat == null || product == null -> Choice.rejected("not_found")
				inProgress(seat.id) -> Choice.rejected("control_in_progress")
				else -> choose(Action.RESTORE, seat, product, connections[seat.vendorId])
			}
			Triple(UUID.fromString(target.targetId), choice, choice.reason)
		}
		if (choices.all { it.third != null }) {
			throw ManagementException("restore_not_available", 422, "operationId", choices.map { mapOf("seatAssignmentId" to it.first.toString(), "reason" to it.third) })
		}
		val operation = try {
			operations.create(tenant, OperationKind.SEAT_RESTORE, actor, choices.map { it.first.toString() }, restores = reclaimId)
		} catch (error: OperationException) {
			if (error.error == OperationError.RESTORE_UNAVAILABLE) fail("restore_not_available", 422, "operationId") else throw error
		}
		start(tenant, operation.id, Action.RESTORE, choices, now)
		requireNotNull(operations.find(tenant, operation.id))
	}

	/** 관리자가 벤더 콘솔에서 조치했다고 확인한다. 조치 대기 중인 대상만 — 원장을 옮기고 대상을 성공으로 끝낸다(확인자 = [actor]). */
	fun confirm(tenant: UUID, actor: UUID, operationId: UUID, rawSeat: String): Operation = write {
		RegisteredProducts.requireManager(jdbc, tenant, actor)
		val (seatId, action) = awaiting(tenant, operationId, rawSeat)
		ledger.applyAdminAction(tenant, seatId, operationId, restore = action == Action.RESTORE)
		operations.confirm(tenant, operationId, seatId.toString(), actor)
	}

	/** 관리자 조치를 하지 않기로 한다. 원장은 그대로이고 대상은 `cancelled` 로 실패한다. */
	fun cancel(tenant: UUID, actor: UUID, operationId: UUID, rawSeat: String): Operation = write {
		RegisteredProducts.requireManager(jdbc, tenant, actor)
		val (seatId, _) = awaiting(tenant, operationId, rawSeat)
		operations.fail(tenant, operationId, seatId.toString(), "cancelled")
	}

	// ---- 주기 실행(벤더 제어) ----

	/**
	 * 다음 벤더 제어 대상을 선점한다. 선점이 없거나 기한이 지난 대기 대상 중 가장 오래된 것. 좌석이 그 사이 제어할 수 없는 상태가 됐으면
	 * 벤더를 부르지 않고 그 대상을 `seat_changed` 로 끝낸 뒤 다음을 본다. 없으면 null.
	 */
	fun claim(worker: String, lease: Duration): Claim? = write {
		require(!lease.isNegative && !lease.isZero) { "선점 기한은 양수여야 한다" }
		var claimed: Claim? = null
		while (claimed == null) {
			val now = now()
			val row = jdbc.sql("""SELECT c.operation_id, c.seat_assignment_id, c.tenant_id, c.action, c.connection_id
					FROM enrollment.seat_controls c
					JOIN enrollment.operation_targets t ON t.operation_id = c.operation_id AND t.target_id = c.seat_assignment_id::text
					WHERE c.method = 'vendor_control' AND t.status = 'pending' AND (c.claimed_until IS NULL OR c.claimed_until <= :now)
					ORDER BY c.requested_at, c.seat_assignment_id LIMIT 1 FOR UPDATE OF c SKIP LOCKED""")
				.param("now", Timestamp.from(now))
				.query { rs, _ -> ControlRow(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getObject(3, UUID::class.java),
					Action.of(rs.getString(4)), rs.getObject(5, UUID::class.java)) }
				.optional().orElse(null) ?: break
			val seat = ledger.seat(row.tenantId, row.seatId)
			if (seat == null || !controllable(row.action, seat.state)) {
				operations.fail(row.tenantId, row.operationId, row.seatId.toString(), "seat_changed")
				continue
			}
			jdbc.sql("""UPDATE enrollment.seat_controls SET claimed_by = :worker, claimed_until = :until, attempts = attempts + 1
					WHERE operation_id = :operation AND seat_assignment_id = :seat""")
				.param("worker", worker).param("until", Timestamp.from(now.plus(lease))).param("operation", row.operationId).param("seat", row.seatId).update()
			claimed = Claim(row.operationId, row.seatId, row.tenantId, row.action, row.connectionId, VendorAccount(seat.account, seat.vendorAccountRef), worker)
		}
		claimed
	}

	/** 벤더가 받아들였다 — 원장에 옮기고 대상을 성공으로 끝낸다. 선점을 잃었으면(기한이 지나 다른 실행이 가져갔다) 아무것도 쓰지 않고 false. */
	fun complete(claim: Claim, result: ControlResult): Boolean = write {
		if (!owns(claim)) return@write false
		ledger.applyControl(claim.tenantId, claim.seatAssignmentId, claim.operationId, restore = claim.action == Action.RESTORE, result)
		release(claim)
		operations.succeed(claim.tenantId, claim.operationId, claim.seatAssignmentId.toString())
		true
	}

	/** 벤더 호출이 실패했다. 원장은 그대로이고 대상은 [reason] 으로 실패한다. 선점을 잃었으면 false. */
	fun fail(claim: Claim, reason: String): Boolean = write {
		if (!owns(claim)) return@write false
		release(claim)
		operations.fail(claim.tenantId, claim.operationId, claim.seatAssignmentId.toString(), reason)
		true
	}

	// ---- 내부 ----

	private data class PreviewRow(val requestedBy: UUID, val expiresAt: Instant, val targets: String, val operationId: UUID?)
	private data class ControlRow(val operationId: UUID, val seatId: UUID, val tenantId: UUID, val action: Action, val connectionId: UUID)

	private fun choose(action: Action, seat: SeatLedger.Seat, product: RegisteredProduct, connection: ConnectionRecord?): Choice =
		choose(action, seat.state, product.kind, product.plan, connection, seat.vendorAccountRef, vendorControl)

	/** 작업을 시작하고 대상마다 실행 방식을 남긴다. 사유가 있는 대상은 실패, 관리자 조치 대상은 조치 대기로 둔다. */
	private fun start(tenant: UUID, operationId: UUID, action: Action, targets: List<Triple<UUID, Choice, String?>>, now: Instant) {
		operations.start(tenant, operationId)
		targets.forEach { (seatId, choice, reason) ->
			if (reason != null) { operations.fail(tenant, operationId, seatId.toString(), reason); return@forEach }
			jdbc.sql("""INSERT INTO enrollment.seat_controls (operation_id, seat_assignment_id, tenant_id, action, method, connection_id, requested_at)
					VALUES (:operation, :seat, :tenant, :action, :method, :connection, :now)""")
				.param("operation", operationId).param("seat", seatId).param("tenant", tenant).param("action", action.wire).param("method", choice.method!!.wire)
				.param("connection", choice.connectionId, Types.OTHER).param("now", Timestamp.from(now)).update()
			if (choice.method == Method.ADMIN_ACTION) {
				operations.awaitAdminAction(tenant, operationId, seatId.toString(), if (action == Action.RELEASE) RELEASE_IN_CONSOLE else RESTORE_IN_CONSOLE)
			}
		}
	}

	/** 경로의 작업·좌석이 그 조직의 관리자 조치 대기 대상인지 확인한다. 없는 것은 404, 조치 대기가 아니면 `409 not_awaiting_admin_action`. */
	private fun awaiting(tenant: UUID, operationId: UUID, rawSeat: String): Pair<UUID, Action> {
		val seatId = uuidOrNull(rawSeat) ?: fail("not_found", 404, "targetId")
		val (action, method) = jdbc.sql("""SELECT action, method FROM enrollment.seat_controls WHERE operation_id = :operation AND seat_assignment_id = :seat AND tenant_id = :tenant
				FOR UPDATE""").param("operation", operationId).param("seat", seatId).param("tenant", tenant)
			.query { rs, _ -> Action.of(rs.getString(1)) to Method.of(rs.getString(2)) }.optional().orElse(null) ?: fail("not_found", 404, "targetId")
		val status = operations.find(tenant, operationId)?.targets?.firstOrNull { it.targetId == seatId.toString() }?.status
		if (method != Method.ADMIN_ACTION || status != OperationTargetStatus.AWAITING_ADMIN_ACTION) fail("not_awaiting_admin_action", 409, "targetId")
		return seatId to action
	}

	/** 그 좌석에 끝나지 않은 회수·복원 대상이 있는가. */
	private fun inProgress(seatId: UUID): Boolean = jdbc.sql("""SELECT EXISTS (SELECT 1 FROM enrollment.seat_controls c
			JOIN enrollment.operation_targets t ON t.operation_id = c.operation_id AND t.target_id = c.seat_assignment_id::text
			WHERE c.seat_assignment_id = :seat AND t.status IN ('pending', 'awaiting_admin_action'))""")
		.param("seat", seatId).query(Boolean::class.java).single()

	private fun owns(claim: Claim): Boolean {
		val owner = jdbc.sql("SELECT claimed_by FROM enrollment.seat_controls WHERE operation_id = :operation AND seat_assignment_id = :seat FOR UPDATE")
			.param("operation", claim.operationId).param("seat", claim.seatAssignmentId).query { rs, _ -> rs.getString(1) }.optional().orElse(null)
		val status = operations.find(claim.tenantId, claim.operationId)?.targets?.firstOrNull { it.targetId == claim.seatAssignmentId.toString() }?.status
		return owner == claim.worker && status == OperationTargetStatus.PENDING
	}

	private fun release(claim: Claim) {
		jdbc.sql("UPDATE enrollment.seat_controls SET claimed_by = NULL, claimed_until = NULL WHERE operation_id = :operation AND seat_assignment_id = :seat")
			.param("operation", claim.operationId).param("seat", claim.seatAssignmentId).update()
	}

	/** 절감 추정(등급 단가 합)과 회수 뒤 미배정 좌석 — 모르는 값이 하나라도 있으면 그 합은 null 이다. */
	private fun impact(tenant: UUID, seats: List<SeatLedger.Seat>, now: Instant): Pair<BigDecimal?, Long?> {
		if (seats.isEmpty()) return null to null
		val products = seats.map { it.vendorId }.distinct().associateWith { RegisteredProducts.find(jdbc, tenant, it, lock = false) }
			.mapValues { (_, product) -> product.takeIf { it.contractStatus(now) == ContractStatus.active && it.tiers.isNotEmpty() } }
		val fees = seats.map { seat -> products[seat.vendorId]?.tiers?.firstOrNull { it.id == seat.tierId }?.monthlyFeePerSeatUsd }
		val savings = fees.takeIf { list -> list.all { it != null } }?.sumOf { it!! }
		val unallocated = seats.groupBy { it.vendorId }.map { (vendorId, reclaimed) ->
			val contracted = products[vendorId]?.contractedSeats ?: return@map null
			val held = jdbc.sql("SELECT count(*) FROM enrollment.seat_assignments WHERE tenant_id = :tenant AND vendor_id = :vendor AND state <> 'released'")
				.param("tenant", tenant).param("vendor", vendorId).query(Long::class.java).single()
			maxOf(contracted - (held - reclaimed.size), 0L)
		}.takeIf { list -> list.all { it != null } }?.sumOf { it!! }
		return savings to unallocated
	}

	private fun uuidOrNull(raw: String): UUID? = try { UUID.fromString(raw).takeIf { it.toString() == raw.lowercase() } } catch (_: IllegalArgumentException) { null }
	private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)
	@Suppress("UNCHECKED_CAST")
	private fun <T> write(block: () -> T): T = tx.execute { block() } as T
	private fun fail(code: String, status: Int, field: String? = null): Nothing = throw ManagementException(code, status, field)

	companion object {
		/** 미리보기가 사는 시간 — 확인 창을 연 사람이 읽고 확정하는 동안이다. 그 뒤에는 다시 계산해야 한다. */
		val PREVIEW_TTL: Duration = Duration.ofMinutes(5)
		/** 회수를 되돌릴 수 있는 기간. 벤더는 기한을 두지 않는다(재배정·재초대는 언제든) — 되돌리기를 "그 회수"에 묶어 두는 기간이다. */
		val RESTORE_WINDOW: Duration = Duration.ofDays(30)
		const val MAX_SEATS = 100
		const val RELEASE_IN_CONSOLE = "release_in_vendor_console"
		const val RESTORE_IN_CONSOLE = "restore_in_vendor_console"

		/** 그 동작을 지금 상태에서 할 수 있는가 — 해제는 배정 좌석, 복원은 해제·해제 예정 좌석. */
		fun controllable(action: Action, state: SeatState): Boolean = when (action) {
			Action.RELEASE -> state == SeatState.ASSIGNED
			Action.RESTORE -> state == SeatState.RELEASED || state == SeatState.PENDING_RELEASE
		}

		/**
		 * 실행 방식 (ADR 0049 §2). 활성 연결이 있고 계약 플랜의 커넥터가 그 연결의 커넥터이며 그 기능을 구현했으면 벤더 제어, 아니면 관리자 조치다.
		 * 순수 함수라 조회 앱도 같은 규칙으로 좌석의 회수 가능 여부를 낸다([vendorControl] 은 조회 앱에서 모르므로 true 로 본다).
		 *
		 * | 사유 | 뜻 |
		 * | --- | --- |
		 * | `not_assigned` | 해제할 수 있는 것은 배정 좌석뿐이다(해제 예정·배정 대기·해제는 아니다) |
		 * | `seat_reassigned` | 복원할 좌석이 이미 다시 보유됐다 |
		 * | `plan_mismatch` | 연결의 커넥터가 지금 계약 플랜의 커넥터가 아니다 |
		 * | `vendor_account_unknown` | 그 기능에 벤더 내부 ID 가 필요한데 없다(연결 전 수동 기록) |
		 * | `connector_unavailable` | 이 배포에 커넥터 호출이 없다 |
		 * | `not_restorable` | 해제 예정 좌석을 벤더 제어 없이 되살릴 수 없다(관리자 조치 전이에 없다) |
		 */
		fun choose(action: Action, state: SeatState, kind: String, plan: String?, connection: ConnectionRecord?, vendorAccountRef: String?,
			vendorControl: Boolean): Choice {
			if (!controllable(action, state)) return Choice.rejected(if (action == Action.RELEASE) "not_assigned" else "seat_reassigned")
			if (connection != null) {
				val connector = ConnectorDescriptors.forPlan(kind, plan)
				if (connector?.id != connection.connector) return Choice.rejected("plan_mismatch")
				if (action.capability in connector.capabilities) {
					if (action.capability in connector.accountRefRequired && vendorAccountRef == null) return Choice.rejected("vendor_account_unknown")
					if (!vendorControl) return Choice.rejected("connector_unavailable")
					return Choice(Method.VENDOR_CONTROL, connection.id, null)
				}
			}
			if (action == Action.RESTORE && state == SeatState.PENDING_RELEASE) return Choice.rejected("not_restorable")
			return Choice(Method.ADMIN_ACTION, null, null)
		}
	}
}
