package com.team376.pulsemetry.persistence.enrollment.operation

import com.team376.pulsemetry.persistence.enrollment.operation.OperationStatus.AWAITING_ADMIN_ACTION
import com.team376.pulsemetry.persistence.enrollment.operation.OperationStatus.FAILED
import com.team376.pulsemetry.persistence.enrollment.operation.OperationStatus.PARTIALLY_FAILED
import com.team376.pulsemetry.persistence.enrollment.operation.OperationStatus.PENDING
import com.team376.pulsemetry.persistence.enrollment.operation.OperationStatus.RUNNING
import com.team376.pulsemetry.persistence.enrollment.operation.OperationStatus.SUCCEEDED
import com.team376.pulsemetry.persistence.enrollment.support.AbstractPersistenceIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 공통 작업 기록의 전이 규칙 (ADR 0039). 기대값은 그 결정에서 온다 —
 * 상태는 대상 결과에서 계산하고, 끝난 대상과 끝난 작업은 바뀌지 않으며, 조치 대기는 확인으로만 성공이 된다.
 */
class OperationStoreTest : AbstractPersistenceIntegrationTest() {
    @Autowired private lateinit var jdbc: JdbcClient
    @Autowired private lateinit var manager: PlatformTransactionManager

    private class TestClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private val clock = TestClock(Instant.parse("2026-09-01T00:00:00Z"))
    private lateinit var store: OperationStore
    private val tenants = mutableListOf<UUID>()
    private lateinit var tenant: UUID
    private lateinit var admin: UUID

    @BeforeEach
    fun setUp() {
        store = OperationStore(jdbc, manager, clock)
        tenant = tenant()
        admin = member(tenant)
    }

    @AfterEach
    fun cleanUp() {
        tenants.forEach { id ->
            jdbc.sql("DELETE FROM enrollment.operation_targets WHERE operation_id IN (SELECT id FROM enrollment.operations WHERE tenant_id=:t)").param("t", id).update()
            // 복원 작업이 회수 작업을 가리키므로 복원부터 지운다.
            jdbc.sql("DELETE FROM enrollment.operations WHERE tenant_id=:t AND restores_operation_id IS NOT NULL").param("t", id).update()
            jdbc.sql("DELETE FROM enrollment.operations WHERE tenant_id=:t").param("t", id).update()
            jdbc.sql("DELETE FROM enrollment.members WHERE tenant_id=:t").param("t", id).update()
            jdbc.sql("DELETE FROM enrollment.tenants WHERE id=:t").param("t", id).update()
        }
    }

    private fun tenant(): UUID = UUID.randomUUID().also {
        jdbc.sql("INSERT INTO enrollment.tenants(id,name) VALUES (:id,'작업 테스트 조직')").param("id", it).update()
        tenants += it
    }

    private fun member(tenantId: UUID): UUID = UUID.randomUUID().also {
        jdbc.sql("INSERT INTO enrollment.members(id,tenant_id,email,role) VALUES (:id,:tenant,:email,'admin')")
            .param("id", it).param("tenant", tenantId).param("email", "admin-$it@example.test").update()
    }

    private fun running(vararg targets: String, kind: OperationKind = OperationKind.INSTALLATION_NOTIFICATION): UUID {
        val id = store.create(tenant, kind, admin, targets.toList()).id
        store.start(tenant, id)
        return id
    }

    private fun rejected(error: OperationError, call: ThrowingCallable) {
        assertThatThrownBy(call).isInstanceOfSatisfying(OperationException::class.java) { assertThat(it.error).isEqualTo(error) }
    }

    private fun statuses(operation: Operation) = operation.targets.associate { it.targetId to it.status.wire }

    @Test fun `작업의 상태는 대상 결과에서 계산한다`() {
        val p = OperationTargetStatus.PENDING
        val a = OperationTargetStatus.AWAITING_ADMIN_ACTION
        val s = OperationTargetStatus.SUCCEEDED
        val f = OperationTargetStatus.FAILED
        assertThat(OperationStatus.of(listOf(p, s, f, a))).isEqualTo(RUNNING)
        assertThat(OperationStatus.of(listOf(a, s, f))).isEqualTo(AWAITING_ADMIN_ACTION)
        assertThat(OperationStatus.of(listOf(s, s))).isEqualTo(SUCCEEDED)
        assertThat(OperationStatus.of(listOf(s, f))).isEqualTo(PARTIALLY_FAILED)
        assertThat(OperationStatus.of(listOf(f, f))).isEqualTo(FAILED)
        assertThat(OperationStatus.of(emptyList())).isEqualTo(FAILED)
    }

    @Test fun `만든 작업은 대기 상태이고 대상은 넣은 순서대로 기다린다`() {
        val created = store.create(tenant, OperationKind.INSTALLATION_NOTIFICATION, admin, listOf("c", "a", "b"))
        assertThat(created.status).isEqualTo(PENDING)
        assertThat(created.kind).isEqualTo(OperationKind.INSTALLATION_NOTIFICATION)
        assertThat(created.tenantId).isEqualTo(tenant)
        assertThat(created.requestedBy).isEqualTo(admin)
        assertThat(created.createdAt).isEqualTo(clock.now)
        assertThat(created.completedAt).isNull()
        assertThat(created.targets.map { it.targetId }).containsExactly("c", "a", "b")
        assertThat(created.targets).allSatisfy {
            assertThat(it.status).isEqualTo(OperationTargetStatus.PENDING)
            assertThat(it.reason).isNull()
            assertThat(it.action).isNull()
            assertThat(it.resolvedAt).isNull()
        }
        assertThat(store.find(tenant, created.id)).isEqualTo(created)
    }

    @Test fun `다른 조직의 구성원이 요청한 작업과 잘못된 대상은 만들지 않는다`() {
        val outsider = member(tenant())
        assertThatThrownBy { store.create(tenant, OperationKind.SEAT_RECLAIM, outsider, listOf("a")) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { store.create(tenant, OperationKind.SEAT_RECLAIM, admin, listOf("a", "a")) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { store.create(tenant, OperationKind.SEAT_RECLAIM, admin, listOf(" ")) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { store.create(tenant, OperationKind.SEAT_RECLAIM, admin, listOf("x".repeat(129))) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { store.create(tenant, OperationKind.SEAT_RECLAIM, admin, List(OperationStore.MAX_TARGETS + 1) { "t$it" }) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.operations WHERE tenant_id=:t").param("t", tenant).query(Int::class.java).single()).isEqualTo(0)
    }

    @Test fun `작업 생성은 호출자의 트랜잭션과 함께 롤백된다`() {
        var id: UUID? = null
        assertThatThrownBy {
            TransactionTemplate(manager).executeWithoutResult {
                id = store.create(tenant, OperationKind.INSTALLATION_NOTIFICATION, admin, listOf("a")).id
                throw IllegalStateException("업무 쓰기 실패")
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(store.find(tenant, id!!)).isNull()
    }

    @Test fun `대상은 시작 전에만 더하고 대상이 없는 작업은 시작하지 못한다`() {
        val id = store.create(tenant, OperationKind.INSTALLATION_NOTIFICATION, admin).id
        rejected(OperationError.NOT_ALLOWED) { store.start(tenant, id) }
        store.addTargets(tenant, id, listOf("a"))
        assertThat(store.addTargets(tenant, id, listOf("b", "c")).targets.map { it.targetId }).containsExactly("a", "b", "c")
        rejected(OperationError.NOT_ALLOWED) { store.addTargets(tenant, id, listOf("a")) }
        assertThat(store.start(tenant, id).status).isEqualTo(RUNNING)
        rejected(OperationError.NOT_ALLOWED) { store.addTargets(tenant, id, listOf("d")) }
        rejected(OperationError.NOT_ALLOWED) { store.start(tenant, id) }
        assertThat(store.find(tenant, id)!!.targets.map { it.targetId }).containsExactly("a", "b", "c")
    }

    @Test fun `시작하지 않은 작업에는 결과를 기록하지 못한다`() {
        val id = store.create(tenant, OperationKind.INSTALLATION_NOTIFICATION, admin, listOf("a")).id
        rejected(OperationError.NOT_ALLOWED) { store.succeed(tenant, id, "a") }
        rejected(OperationError.NOT_ALLOWED) { store.fail(tenant, id, "a", "mail_failed") }
        rejected(OperationError.NOT_ALLOWED) { store.awaitAdminAction(tenant, id, "a", "revoke_in_vendor_console") }
        assertThat(store.find(tenant, id)!!.status).isEqualTo(PENDING)
        assertThat(statuses(store.find(tenant, id)!!)).containsEntry("a", "pending")
    }

    @Test fun `모든 대상이 성공해야 성공이고 끝난 작업은 바뀌지 않는다`() {
        val id = running("a", "b")
        clock.now = clock.now.plusSeconds(5)
        val half = store.succeed(tenant, id, "a")
        assertThat(half.status).isEqualTo(RUNNING)
        assertThat(half.completedAt).isNull()
        assertThat(half.targets.first().resolvedAt).isEqualTo(clock.now)
        clock.now = clock.now.plusSeconds(5)
        val done = store.succeed(tenant, id, "b")
        assertThat(done.status).isEqualTo(SUCCEEDED)
        assertThat(done.completedAt).isEqualTo(clock.now)
        rejected(OperationError.NOT_ALLOWED) { store.fail(tenant, id, "a", "late_failure") }
        rejected(OperationError.NOT_ALLOWED) { store.abort(tenant, id, "cancelled") }
        assertThat(store.find(tenant, id)).isEqualTo(done)
    }

    @Test fun `일부만 성공하면 부분 실패이고 실패한 대상에 사유가 남는다`() {
        val id = running("a", "b", "c")
        store.succeed(tenant, id, "a")
        assertThat(store.fail(tenant, id, "b", "recipient_rejected").status).isEqualTo(RUNNING)
        // 작업이 진행 중이어도 끝난 대상은 바뀌지 않는다.
        rejected(OperationError.NOT_ALLOWED) { store.succeed(tenant, id, "b") }
        rejected(OperationError.NOT_ALLOWED) { store.fail(tenant, id, "a", "late_failure") }
        rejected(OperationError.TARGET_NOT_FOUND) { store.succeed(tenant, id, "zzz") }
        val done = store.succeed(tenant, id, "c")
        assertThat(done.status).isEqualTo(PARTIALLY_FAILED)
        assertThat(done.completedAt).isNotNull()
        assertThat(statuses(done)).containsExactlyInAnyOrderEntriesOf(mapOf("a" to "succeeded", "b" to "failed", "c" to "succeeded"))
        assertThat(done.targets.associate { it.targetId to it.reason }).containsExactlyInAnyOrderEntriesOf(mapOf("a" to null, "b" to "recipient_rejected", "c" to null))
    }

    @Test fun `성공한 대상이 없으면 실패다`() {
        val id = running("a", "b")
        store.fail(tenant, id, "a", "smtp_unavailable")
        val done = store.fail(tenant, id, "b", "smtp_unavailable")
        assertThat(done.status).isEqualTo(FAILED)
        assertThat(done.completedAt).isNotNull()
    }

    @Test fun `사유와 조치는 분류 코드만 받는다`() {
        val id = running("a")
        assertThatThrownBy { store.fail(tenant, id, "a", "550 5.1.1 User unknown") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { store.fail(tenant, id, "a", "") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { store.awaitAdminAction(tenant, id, "a", "콘솔에서 해지") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { store.abort(tenant, id, "x".repeat(65)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(store.find(tenant, id)!!.status).isEqualTo(RUNNING)
    }

    @Test fun `조치 대기는 기다리는 대상이 없을 때의 작업 상태이고 확인으로만 성공이 된다`() {
        val id = running("auto", "manual", kind = OperationKind.SEAT_RECLAIM)
        val waiting = store.awaitAdminAction(tenant, id, "manual", "revoke_in_vendor_console")
        // 자동으로 처리할 대상이 남아 있는 동안은 진행 중이다.
        assertThat(waiting.status).isEqualTo(RUNNING)
        assertThat(waiting.targets.single { it.targetId == "manual" }.action).isEqualTo("revoke_in_vendor_console")
        val parked = store.succeed(tenant, id, "auto")
        assertThat(parked.status).isEqualTo(AWAITING_ADMIN_ACTION)
        assertThat(parked.completedAt).isNull()

        // 자동 성공 처리는 없다.
        rejected(OperationError.NOT_ALLOWED) { store.succeed(tenant, id, "manual") }
        rejected(OperationError.NOT_ALLOWED) { store.awaitAdminAction(tenant, id, "manual", "revoke_in_vendor_console") }
        assertThatThrownBy { store.confirm(tenant, id, "manual", member(tenant())) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(store.find(tenant, id)).isEqualTo(parked)

        val confirmer = member(tenant)
        clock.now = clock.now.plusSeconds(60)
        val done = store.confirm(tenant, id, "manual", confirmer)
        assertThat(done.status).isEqualTo(SUCCEEDED)
        assertThat(done.completedAt).isEqualTo(clock.now)
        val manual = done.targets.single { it.targetId == "manual" }
        assertThat(manual.status).isEqualTo(OperationTargetStatus.SUCCEEDED)
        assertThat(manual.confirmedBy).isEqualTo(confirmer)
        assertThat(manual.action).isEqualTo("revoke_in_vendor_console")
        assertThat(manual.resolvedAt).isEqualTo(clock.now)
        assertThat(done.targets.single { it.targetId == "auto" }.confirmedBy).isNull()
    }

    @Test fun `기다리던 대상은 확인할 수 없고 조치 대기는 실패로 닫을 수 있다`() {
        val id = running("a", "b", kind = OperationKind.SEAT_RECLAIM)
        rejected(OperationError.NOT_ALLOWED) { store.confirm(tenant, id, "a", admin) }
        store.awaitAdminAction(tenant, id, "a", "revoke_in_vendor_console")
        store.awaitAdminAction(tenant, id, "b", "revoke_in_vendor_console")
        store.confirm(tenant, id, "a", admin)
        val done = store.fail(tenant, id, "b", "confirmation_expired")
        assertThat(done.status).isEqualTo(PARTIALLY_FAILED)
        val expired = done.targets.single { it.targetId == "b" }
        assertThat(expired.reason).isEqualTo("confirmation_expired")
        assertThat(expired.confirmedBy).isNull()
    }

    @Test fun `중단하면 끝나지 않은 대상이 모두 실패하고 성공한 대상은 남는다`() {
        val untouched = store.create(tenant, OperationKind.RETENTION_CLEANUP, admin, listOf("analysis_source")).id
        val never = store.abort(tenant, untouched, "superseded")
        assertThat(never.status).isEqualTo(FAILED)
        assertThat(never.completedAt).isEqualTo(clock.now)
        assertThat(never.targets.single().reason).isEqualTo("superseded")

        val empty = store.create(tenant, OperationKind.INSTALLATION_NOTIFICATION, admin).id
        assertThat(store.abort(tenant, empty, "cancelled").status).isEqualTo(FAILED)

        val id = running("a", "b", "c", kind = OperationKind.SEAT_RECLAIM)
        store.succeed(tenant, id, "a")
        store.awaitAdminAction(tenant, id, "b", "revoke_in_vendor_console")
        val aborted = store.abort(tenant, id, "worker_failed")
        assertThat(aborted.status).isEqualTo(PARTIALLY_FAILED)
        assertThat(statuses(aborted)).containsExactlyInAnyOrderEntriesOf(mapOf("a" to "succeeded", "b" to "failed", "c" to "failed"))
        assertThat(aborted.targets.filter { it.targetId != "a" }.map { it.reason }).containsOnly("worker_failed")
        rejected(OperationError.NOT_ALLOWED) { store.abort(tenant, id, "worker_failed") }
    }

    @Test fun `다른 조직에서는 작업이 없는 것과 같고 어떤 전이도 하지 못한다`() {
        val other = tenant()
        val outsider = member(other)
        val id = running("a", "b", kind = OperationKind.SEAT_RECLAIM)
        store.awaitAdminAction(tenant, id, "b", "revoke_in_vendor_console")
        val before = store.find(tenant, id)

        assertThat(store.find(other, id)).isNull()
        rejected(OperationError.NOT_FOUND) { store.succeed(other, id, "a") }
        rejected(OperationError.NOT_FOUND) { store.fail(other, id, "a", "mail_failed") }
        rejected(OperationError.NOT_FOUND) { store.awaitAdminAction(other, id, "a", "revoke_in_vendor_console") }
        rejected(OperationError.NOT_FOUND) { store.confirm(other, id, "b", outsider) }
        rejected(OperationError.NOT_FOUND) { store.abort(other, id, "cancelled") }
        rejected(OperationError.NOT_FOUND) { store.addTargets(other, id, listOf("c")) }
        rejected(OperationError.NOT_FOUND) { store.start(other, id) }
        rejected(OperationError.NOT_FOUND) { store.allowRestore(other, id, clock.now.plusSeconds(60)) }
        rejected(OperationError.NOT_FOUND) { store.attachRetention(other, id, UUID.randomUUID()) }
        rejected(OperationError.NOT_FOUND) { store.create(other, OperationKind.SEAT_RESTORE, outsider, listOf("a"), restores = id) }
        rejected(OperationError.NOT_FOUND) { store.succeed(tenant, UUID.randomUUID(), "a") }
        assertThat(store.find(tenant, id)).isEqualTo(before)
    }

    @Test fun `같은 작업의 결과를 동시에 기록해도 상태가 대상과 어긋나지 않는다`() {
        val targets = List(12) { "t$it" }
        val id = running(*targets.toTypedArray())
        val pool = Executors.newFixedThreadPool(6)
        try {
            pool.invokeAll(targets.map { target -> Callable { store.succeed(tenant, id, target) } }).forEach { it.get(30, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        val done = store.find(tenant, id)!!
        assertThat(done.targets).allSatisfy { assertThat(it.status).isEqualTo(OperationTargetStatus.SUCCEEDED) }
        assertThat(done.status).isEqualTo(SUCCEEDED)
        assertThat(done.completedAt).isNotNull()
    }

    @Test fun `회수는 기한 안에 한 번만 되돌릴 수 있고 복원이 실패하면 다시 되돌릴 수 있다`() {
        val id = running("a", "b", "c", kind = OperationKind.SEAT_RECLAIM)
        store.succeed(tenant, id, "a")
        store.succeed(tenant, id, "b")
        // 끝나기 전에는 기한이 있어도 되돌리지 못한다.
        val until = clock.now.plus(Duration.ofDays(7))
        assertThat(store.allowRestore(tenant, id, until).canRestore(clock.now)).isFalse()
        rejected(OperationError.NOT_ALLOWED) { store.allowRestore(tenant, id, until.plusSeconds(1)) }
        rejected(OperationError.RESTORE_UNAVAILABLE) { store.create(tenant, OperationKind.SEAT_RESTORE, admin, listOf("a"), restores = id) }
        val reclaim = store.fail(tenant, id, "c", "vendor_rejected")
        assertThat(reclaim.status).isEqualTo(PARTIALLY_FAILED)
        assertThat(reclaim.restoreUntil).isEqualTo(until)
        assertThat(reclaim.canRestore(clock.now)).isTrue()
        assertThat(reclaim.canRestore(until)).isFalse()

        // 회수에 성공한 대상만 되돌린다.
        rejected(OperationError.RESTORE_UNAVAILABLE) { store.create(tenant, OperationKind.SEAT_RESTORE, admin, listOf("c"), restores = id) }
        rejected(OperationError.RESTORE_UNAVAILABLE) { store.create(tenant, OperationKind.SEAT_RESTORE, admin, emptyList(), restores = id) }
        assertThatThrownBy { store.create(tenant, OperationKind.SEAT_RESTORE, admin, listOf("a")) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { store.create(tenant, OperationKind.SEAT_RECLAIM, admin, listOf("a"), restores = id) }.isInstanceOf(IllegalArgumentException::class.java)

        val restore = store.create(tenant, OperationKind.SEAT_RESTORE, admin, listOf("a", "b"), restores = id)
        assertThat(restore.restoresOperationId).isEqualTo(id)
        assertThat(restore.canRestore(clock.now)).isFalse()
        assertThat(store.find(tenant, id)!!.restored).isTrue()
        assertThat(store.find(tenant, id)!!.canRestore(clock.now)).isFalse()
        rejected(OperationError.RESTORE_UNAVAILABLE) { store.create(tenant, OperationKind.SEAT_RESTORE, admin, listOf("a"), restores = id) }
        rejected(OperationError.NOT_ALLOWED) { store.addTargets(tenant, restore.id, listOf("c")) }

        // 복원이 전부 실패하면 회수는 다시 되돌릴 수 있다 — 기한 안에서만.
        store.abort(tenant, restore.id, "vendor_unavailable")
        assertThat(store.find(tenant, id)!!.canRestore(clock.now)).isTrue()
        clock.now = until
        rejected(OperationError.RESTORE_UNAVAILABLE) { store.create(tenant, OperationKind.SEAT_RESTORE, admin, listOf("a"), restores = id) }
        clock.now = until.minusSeconds(1)
        assertThat(store.create(tenant, OperationKind.SEAT_RESTORE, admin, listOf("a"), restores = id).status).isEqualTo(PENDING)
    }

    @Test fun `복원 기한은 회수 작업에만, 실패하지 않았을 때만 정한다`() {
        val notice = running("a")
        rejected(OperationError.NOT_ALLOWED) { store.allowRestore(tenant, notice, clock.now.plusSeconds(60)) }
        val failed = running("a", kind = OperationKind.SEAT_RECLAIM)
        store.fail(tenant, failed, "a", "vendor_rejected")
        rejected(OperationError.NOT_ALLOWED) { store.allowRestore(tenant, failed, clock.now.plusSeconds(60)) }
        val reclaim = running("a", kind = OperationKind.SEAT_RECLAIM)
        rejected(OperationError.NOT_ALLOWED) { store.allowRestore(tenant, reclaim, clock.now) }
        // 기한이 없는 회수는 끝나도 되돌릴 수 없다.
        assertThat(store.succeed(tenant, reclaim, "a").canRestore(clock.now)).isFalse()
    }

    @Test fun `삭제 실행 기록은 끝나지 않은 보존 정리 작업에만 붙이고 다시 붙이면 바뀐다`() {
        val id = running("analysis_source", kind = OperationKind.RETENTION_CLEANUP)
        assertThat(store.find(tenant, id)!!.retentionOperationId).isNull()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        assertThat(store.attachRetention(tenant, id, first).retentionOperationId).isEqualTo(first)
        assertThat(store.attachRetention(tenant, id, second).retentionOperationId).isEqualTo(second)
        store.succeed(tenant, id, "analysis_source")
        rejected(OperationError.NOT_ALLOWED) { store.attachRetention(tenant, id, first) }
        rejected(OperationError.NOT_ALLOWED) { store.attachRetention(tenant, running("a"), first) }
        assertThat(store.find(tenant, id)!!.retentionOperationId).isEqualTo(second)
    }
}
