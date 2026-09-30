package com.team376.pulsemetry.persistence.enrollment.operation

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** 작업 종류. [OperationTarget.targetId] 가 무엇의 ID인지는 종류가 정한다(ADR 0039). */
enum class OperationKind(val wire: String) {
    SEAT_RECLAIM("seat_reclaim"),
    SEAT_RESTORE("seat_restore"),
    INSTALLATION_NOTIFICATION("installation_notification"),
    RETENTION_CLEANUP("retention_cleanup"),

    /** 관리자의 "지금 동기화" 요청(ADR 0048 §7). 대상은 벤더 연결 ID 하나다. */
    SEAT_SYNC("seat_sync"),
    ;

    companion object {
        fun of(wire: String): OperationKind = entries.single { it.wire == wire }
    }
}

/** 작업의 상태. [closed] 인 상태는 다시 바뀌지 않는다. */
enum class OperationStatus(val wire: String, val closed: Boolean) {
    PENDING("pending", false),
    RUNNING("running", false),

    /** 자동으로 할 일은 끝났고, 관리자가 시스템 밖에서 조치하고 확인해야 끝난다. */
    AWAITING_ADMIN_ACTION("awaiting_admin_action", false),
    SUCCEEDED("succeeded", true),
    PARTIALLY_FAILED("partially_failed", true),
    FAILED("failed", true),
    ;

    companion object {
        fun of(wire: String): OperationStatus = entries.single { it.wire == wire }

        /**
         * 시작한 작업의 상태를 대상 결과에서 계산한다. 기다리는 대상이 남아 있으면 진행 중이고, 조치 대기만 남았으면 조치 대기다.
         * 모두 끝났으면 성공한 대상이 전부일 때만 성공이다 — 하나라도 실패했으면 부분 실패, 성공이 없으면 실패다.
         */
        fun of(targets: Collection<OperationTargetStatus>): OperationStatus = when {
            targets.any { it == OperationTargetStatus.PENDING } -> RUNNING
            targets.any { it == OperationTargetStatus.AWAITING_ADMIN_ACTION } -> AWAITING_ADMIN_ACTION
            targets.none { it == OperationTargetStatus.SUCCEEDED } -> FAILED
            targets.all { it == OperationTargetStatus.SUCCEEDED } -> SUCCEEDED
            else -> PARTIALLY_FAILED
        }
    }
}

enum class OperationTargetStatus(val wire: String, val closed: Boolean) {
    PENDING("pending", false),
    AWAITING_ADMIN_ACTION("awaiting_admin_action", false),
    SUCCEEDED("succeeded", true),
    FAILED("failed", true),
    ;

    companion object {
        fun of(wire: String): OperationTargetStatus = entries.single { it.wire == wire }
    }
}

/**
 * 대상 하나의 결과. [reason] 은 실패 분류 코드, [action] 은 관리자가 시스템 밖에서 해야 하는 조치의 코드다.
 * [confirmedBy] 는 그 조치를 했다고 확인한 구성원이다.
 */
data class OperationTarget(val targetId: String, val status: OperationTargetStatus, val reason: String?, val action: String?,
    val confirmedBy: UUID?, val resolvedAt: Instant?)

/** 작업 하나와 그 대상들([targets] 는 추가한 순서). [restored] 는 이 회수를 되돌리는 살아 있는 복원 작업이 있다는 뜻이다. */
data class Operation(val id: UUID, val tenantId: UUID, val kind: OperationKind, val status: OperationStatus, val requestedBy: UUID,
    val createdAt: Instant, val completedAt: Instant?, val restoreUntil: Instant?, val restoresOperationId: UUID?, val restored: Boolean,
    val retentionOperationId: UUID?, val targets: List<OperationTarget>) {
    /** 되돌릴 수 있는 것은 성공한 대상이 있는 끝난 회수이고, 기한 안이며, 아직 되돌리는 중이 아닐 때뿐이다. */
    fun canRestore(now: Instant): Boolean = kind == OperationKind.SEAT_RECLAIM &&
        (status == OperationStatus.SUCCEEDED || status == OperationStatus.PARTIALLY_FAILED) &&
        restoreUntil != null && now.isBefore(restoreUntil) && !restored
}

enum class OperationError {
    /** 그 조직에 그런 작업이 없다. 다른 조직의 작업도 여기다. */
    NOT_FOUND,
    TARGET_NOT_FOUND,

    /** 지금 상태에서 할 수 없는 전이다. */
    NOT_ALLOWED,
    RESTORE_UNAVAILABLE,
}

class OperationException(val error: OperationError, message: String) : RuntimeException(message)

/** 작업 조회. 읽기만 하므로 읽기 전용 계정으로도 조립할 수 있다. 조직이 다르면 없는 것과 같다. */
class OperationReader(private val jdbc: JdbcClient) {
    fun find(tenantId: UUID, operationId: UUID): Operation? {
        val targets = jdbc.sql("""SELECT target_id,status,reason,action,confirmed_by,resolved_at FROM enrollment.operation_targets
            WHERE operation_id=:id ORDER BY position""").param("id", operationId).query { rs, _ ->
            OperationTarget(rs.getString("target_id"), OperationTargetStatus.of(rs.getString("status")), rs.getString("reason"), rs.getString("action"),
                rs.getObject("confirmed_by", UUID::class.java), rs.instant("resolved_at"))
        }.list()
        return jdbc.sql("""SELECT o.id,o.tenant_id,o.kind,o.status,o.requested_by,o.created_at,o.completed_at,o.restore_until,o.restores_operation_id,
            o.retention_operation_id,EXISTS(SELECT 1 FROM enrollment.operations r WHERE r.restores_operation_id=o.id AND r.status<>'failed') AS restored
            FROM enrollment.operations o WHERE o.id=:id AND o.tenant_id=:tenant""").param("id", operationId).param("tenant", tenantId).query { rs, _ ->
            Operation(rs.getObject("id", UUID::class.java), rs.getObject("tenant_id", UUID::class.java), OperationKind.of(rs.getString("kind")),
                OperationStatus.of(rs.getString("status")), rs.getObject("requested_by", UUID::class.java), requireNotNull(rs.instant("created_at")),
                rs.instant("completed_at"), rs.instant("restore_until"), rs.getObject("restores_operation_id", UUID::class.java), rs.getBoolean("restored"),
                rs.getObject("retention_operation_id", UUID::class.java), targets)
        }.optional().orElse(null)
    }

    private fun ResultSet.instant(column: String): Instant? = getTimestamp(column)?.toInstant()
}

/**
 * 공통 작업 기록의 쓰기 (ADR 0039). 빈이 아니다 — 조립은 앱이 한다.
 *
 * 모든 연산은 조직 범위다. 호출자의 트랜잭션이 있으면 거기에 참여하므로, 명령은 업무 쓰기와 작업 생성을 함께 커밋한다.
 * 작업의 상태는 직접 쓰지 않는다 — 시작([start])과 중단([abort])을 빼면 대상 결과를 기록할 때마다 다시 계산한다.
 * 끝난 대상과 끝난 작업은 바뀌지 않는다. 허용하지 않는 전이는 [OperationException] 이다.
 */
class OperationStore(private val jdbc: JdbcClient, manager: PlatformTransactionManager, private val clock: Clock) {
    private val tx = TransactionTemplate(manager)
    private val reader = OperationReader(jdbc)

    fun find(tenantId: UUID, operationId: UUID): Operation? = reader.find(tenantId, operationId)

    /**
     * 작업을 `pending` 으로 만든다. [requestedBy] 는 그 조직의 구성원이어야 한다.
     * 복원([OperationKind.SEAT_RESTORE])은 되돌릴 회수 작업([restores])을 가리켜야 하고, 대상은 그 회수에서 성공한 대상이어야 한다.
     */
    fun create(tenantId: UUID, kind: OperationKind, requestedBy: UUID, targetIds: List<String> = emptyList(), restores: UUID? = null): Operation = write {
        require((kind == OperationKind.SEAT_RESTORE) == (restores != null)) { "복원 작업만, 그리고 복원 작업은 반드시 회수 작업을 가리킨다" }
        val ids = checked(targetIds, 0)
        val now = clock.instant()
        if (restores != null) {
            val reclaim = locked(tenantId, restores)
            if (!reclaim.canRestore(now)) throw OperationException(OperationError.RESTORE_UNAVAILABLE, "되돌릴 수 없는 회수 작업이다")
            val reclaimed = reclaim.targets.filter { it.status == OperationTargetStatus.SUCCEEDED }.map { it.targetId }
            if (ids.isEmpty() || !reclaimed.containsAll(ids)) throw OperationException(OperationError.RESTORE_UNAVAILABLE, "회수에 성공한 대상만 되돌린다")
        }
        val id = UUID.randomUUID()
        val inserted = jdbc.sql("""INSERT INTO enrollment.operations(id,tenant_id,kind,requested_by,created_at,restores_operation_id)
            SELECT :id,m.tenant_id,:kind,m.id,CAST(:now AS timestamptz),CAST(:restores AS uuid)
            FROM enrollment.members m WHERE m.id=:member AND m.tenant_id=:tenant""")
            .param("id", id).param("tenant", tenantId).param("kind", kind.wire).param("now", Timestamp.from(now))
            .param("restores", restores).param("member", requestedBy).update()
        require(inserted == 1) { "작업을 요청한 구성원이 그 조직에 없다" }
        insertTargets(id, 0, ids)
        current(tenantId, id)
    }

    /** 대상을 더한다. 아직 시작하지 않은 작업에만 더할 수 있다. */
    fun addTargets(tenantId: UUID, operationId: UUID, targetIds: List<String>): Operation = write {
        val operation = locked(tenantId, operationId)
        if (operation.status != OperationStatus.PENDING) throw notAllowed("시작한 작업에는 대상을 더하지 못한다")
        if (operation.kind == OperationKind.SEAT_RESTORE) throw notAllowed("복원 대상은 만들 때 정한다")
        val ids = checked(targetIds, operation.targets.size)
        if (ids.any { id -> operation.targets.any { it.targetId == id } }) throw notAllowed("이미 있는 대상이다")
        insertTargets(operationId, operation.targets.size, ids)
        current(tenantId, operationId)
    }

    /** `pending` → `running`. 대상이 하나도 없는 작업은 시작하지 못한다. */
    fun start(tenantId: UUID, operationId: UUID): Operation = write {
        val operation = locked(tenantId, operationId)
        if (operation.status != OperationStatus.PENDING) throw notAllowed("대기 중인 작업만 시작한다")
        if (operation.targets.isEmpty()) throw notAllowed("대상이 없는 작업은 시작하지 못한다")
        jdbc.sql("UPDATE enrollment.operations SET status='running' WHERE id=:id").param("id", operationId).update()
        current(tenantId, operationId)
    }

    /** 기다리던 대상이 성공했다. 조치 대기 중인 대상은 [confirm] 으로만 성공이 된다. */
    fun succeed(tenantId: UUID, operationId: UUID, targetId: String): Operation =
        settle(tenantId, operationId, targetId, setOf(OperationTargetStatus.PENDING)) { now ->
            jdbc.sql("UPDATE enrollment.operation_targets SET status='succeeded',resolved_at=:now WHERE operation_id=:id AND target_id=:target")
                .param("now", Timestamp.from(now))
        }

    /** 대상이 실패했다. [reason] 은 분류 코드다 — 외부 응답 원문을 넣지 않는다. 조치 대기 중인 대상의 취소·기한 만료도 이것이다. */
    fun fail(tenantId: UUID, operationId: UUID, targetId: String, reason: String): Operation {
        code(reason)
        return settle(tenantId, operationId, targetId, OPEN) { now ->
            jdbc.sql("UPDATE enrollment.operation_targets SET status='failed',reason=:reason,resolved_at=:now WHERE operation_id=:id AND target_id=:target")
                .param("reason", reason).param("now", Timestamp.from(now))
        }
    }

    /** 이 대상은 시스템이 끝낼 수 없다 — 관리자가 [action] 을 하고 확인해야 한다. */
    fun awaitAdminAction(tenantId: UUID, operationId: UUID, targetId: String, action: String): Operation {
        code(action)
        return settle(tenantId, operationId, targetId, setOf(OperationTargetStatus.PENDING)) { _ ->
            jdbc.sql("UPDATE enrollment.operation_targets SET status='awaiting_admin_action',action=:action WHERE operation_id=:id AND target_id=:target")
                .param("action", action)
        }
    }

    /** 관리자가 조치를 했다고 확인했다. 조치 대기 중인 대상만, 그 조직의 구성원만 확인한다. */
    fun confirm(tenantId: UUID, operationId: UUID, targetId: String, confirmedBy: UUID): Operation =
        settle(tenantId, operationId, targetId, setOf(OperationTargetStatus.AWAITING_ADMIN_ACTION)) { now ->
            val member = jdbc.sql("SELECT count(*) FROM enrollment.members WHERE id=:member AND tenant_id=:tenant")
                .param("member", confirmedBy).param("tenant", tenantId).query(Int::class.java).single()
            require(member == 1) { "조치를 확인한 구성원이 그 조직에 없다" }
            jdbc.sql("""UPDATE enrollment.operation_targets SET status='succeeded',confirmed_by=:member,resolved_at=:now
                WHERE operation_id=:id AND target_id=:target""").param("member", confirmedBy).param("now", Timestamp.from(now))
        }

    /**
     * 작업을 중단한다. 끝나지 않은 대상이 모두 [reason] 으로 실패한다. 이미 성공한 대상은 성공으로 남는다 —
     * 그래서 결과는 실패이거나 부분 실패다. 시작하지 않은 작업도 중단할 수 있다.
     */
    fun abort(tenantId: UUID, operationId: UUID, reason: String): Operation = write {
        code(reason)
        val operation = locked(tenantId, operationId)
        if (operation.status.closed) throw notAllowed("끝난 작업은 바뀌지 않는다")
        val now = clock.instant()
        jdbc.sql("""UPDATE enrollment.operation_targets SET status='failed',reason=:reason,resolved_at=:now
            WHERE operation_id=:id AND status IN ('pending','awaiting_admin_action')""")
            .param("reason", reason).param("now", Timestamp.from(now)).param("id", operationId).update()
        recompute(operationId, now)
        current(tenantId, operationId)
    }

    /** 이 회수를 [until] 까지 되돌릴 수 있다고 기록한다. 한 번만 정한다. */
    fun allowRestore(tenantId: UUID, operationId: UUID, until: Instant): Operation = write {
        val operation = locked(tenantId, operationId)
        if (operation.kind != OperationKind.SEAT_RECLAIM || operation.status == OperationStatus.FAILED || operation.restoreUntil != null ||
            !until.isAfter(operation.createdAt)) throw notAllowed("복원 기한을 정할 수 없는 작업이다")
        jdbc.sql("UPDATE enrollment.operations SET restore_until=:until WHERE id=:id").param("until", Timestamp.from(until)).param("id", operationId).update()
        current(tenantId, operationId)
    }

    /** 보존 정리 작업이 가리키는 삭제 실행 기록(`telemetry_ops.retention_operations`)을 바꾼다. 다시 실행하면 가장 최근 실행을 가리킨다. */
    fun attachRetention(tenantId: UUID, operationId: UUID, retentionOperationId: UUID): Operation = write {
        val operation = locked(tenantId, operationId)
        if (operation.kind != OperationKind.RETENTION_CLEANUP || operation.status.closed) throw notAllowed("삭제 실행 기록을 붙일 수 없는 작업이다")
        jdbc.sql("UPDATE enrollment.operations SET retention_operation_id=:retention WHERE id=:id")
            .param("retention", retentionOperationId).param("id", operationId).update()
        current(tenantId, operationId)
    }

    private fun settle(tenantId: UUID, operationId: UUID, targetId: String, from: Set<OperationTargetStatus>,
        update: (Instant) -> JdbcClient.StatementSpec): Operation = write {
        val operation = locked(tenantId, operationId)
        if (operation.status != OperationStatus.RUNNING && operation.status != OperationStatus.AWAITING_ADMIN_ACTION) {
            throw notAllowed("시작했고 끝나지 않은 작업에만 결과를 기록한다")
        }
        val target = operation.targets.firstOrNull { it.targetId == targetId }
            ?: throw OperationException(OperationError.TARGET_NOT_FOUND, "그 작업에 없는 대상이다")
        if (target.status !in from) throw notAllowed("대상의 지금 상태에서 할 수 없는 전이다")
        val now = clock.instant()
        update(now).param("id", operationId).param("target", targetId).update()
        recompute(operationId, now)
        current(tenantId, operationId)
    }

    private fun recompute(operationId: UUID, now: Instant) {
        val targets = jdbc.sql("SELECT status FROM enrollment.operation_targets WHERE operation_id=:id").param("id", operationId)
            .query { rs, _ -> OperationTargetStatus.of(rs.getString(1)) }.list()
        val status = OperationStatus.of(targets)
        if (status.closed) {
            jdbc.sql("UPDATE enrollment.operations SET status=:status,completed_at=:now WHERE id=:id")
                .param("status", status.wire).param("now", Timestamp.from(now)).param("id", operationId).update()
        } else {
            jdbc.sql("UPDATE enrollment.operations SET status=:status WHERE id=:id").param("status", status.wire).param("id", operationId).update()
        }
    }

    private fun insertTargets(operationId: UUID, offset: Int, ids: List<String>) {
        ids.forEachIndexed { index, target ->
            jdbc.sql("INSERT INTO enrollment.operation_targets(operation_id,target_id,position) VALUES (:id,:target,:position)")
                .param("id", operationId).param("target", target).param("position", offset + index).update()
        }
    }

    /** 작업 행을 잠그고 읽는다. 같은 작업의 전이는 한 번에 하나만 진행한다. */
    private fun locked(tenantId: UUID, operationId: UUID): Operation {
        jdbc.sql("SELECT id FROM enrollment.operations WHERE id=:id AND tenant_id=:tenant FOR UPDATE").param("id", operationId).param("tenant", tenantId)
            .query(UUID::class.java).optional().orElseThrow { OperationException(OperationError.NOT_FOUND, "그 조직에 없는 작업이다") }
        return current(tenantId, operationId)
    }

    private fun current(tenantId: UUID, operationId: UUID): Operation = requireNotNull(reader.find(tenantId, operationId))

    private fun checked(targetIds: List<String>, existing: Int): List<String> {
        require(targetIds.all { it.isNotBlank() && it.length <= 128 }) { "대상 ID는 1~128자다" }
        require(targetIds.distinct().size == targetIds.size) { "같은 대상을 두 번 넣지 않는다" }
        require(existing + targetIds.size <= MAX_TARGETS) { "한 작업의 대상은 ${MAX_TARGETS}개까지다" }
        return targetIds
    }

    private fun code(value: String) = require(CODE.matches(value)) { "사유·조치는 영문 소문자·숫자·밑줄의 분류 코드다" }

    private fun notAllowed(message: String) = OperationException(OperationError.NOT_ALLOWED, message)

    private fun <T : Any> write(block: () -> T): T = requireNotNull(tx.execute { block() })

    companion object {
        const val MAX_TARGETS = 1000
        private val OPEN = setOf(OperationTargetStatus.PENDING, OperationTargetStatus.AWAITING_ADMIN_ACTION)
        private val CODE = Regex("[a-z][a-z0-9_]{0,63}")
    }
}
