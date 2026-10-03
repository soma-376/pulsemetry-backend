package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.authentication.DashboardPrincipal
import com.team376.pulsemetry.dashboard.authentication.Role
import com.team376.pulsemetry.dashboard.support.AbstractDashboardApiTest
import com.team376.pulsemetry.dashboard.support.DashboardHttp
import com.team376.pulsemetry.dashboard.support.DashboardTestStores
import com.team376.pulsemetry.dashboard.support.SourceFixtures
import com.team376.pulsemetry.dashboard.support.TestDashboardAuthenticator
import com.team376.pulsemetry.persistence.enrollment.operation.OperationKind
import com.team376.pulsemetry.persistence.enrollment.operation.OperationStore
import com.team376.pulsemetry.persistence.telemetryops.RetentionDeletionCounts
import com.team376.pulsemetry.persistence.telemetryops.RetentionOperationStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import tools.jackson.databind.JsonNode
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * 작업 상태 조회 (ADR 0039). 작업은 쓰기 주체의 저장 연산으로 만든다 — 이 앱은 읽기만 한다.
 * 응답 키는 화면이 쓰는 `OperationResponse` 의 키에 가산 키(`results[].action`, `retention`)를 더한 것이다.
 */
class OperationApiTest : AbstractDashboardApiTest() {
    private val dataSource = DriverManagerDataSource(DashboardTestStores.postgres.jdbcUrl, DashboardTestStores.postgres.username, DashboardTestStores.postgres.password)
    private fun store(clock: Clock = Clock.systemUTC()) = OperationStore(JdbcClient.create(dataSource), DataSourceTransactionManager(dataSource), clock)
    private val retention = RetentionOperationStore(dataSource)

    private fun organization(): Pair<UUID, UUID> {
        val tenant = DashboardTestStores.insertTenant()
        return tenant to SourceFixtures.insertMember(tenant, "admin-${UUID.randomUUID()}@example.test", role = "admin")
    }

    private fun get(tenant: UUID, operation: Any, target: UUID = tenant, role: Role = Role.ADMIN) = http.send(
        "/api/v1/organizations/$target/operations/$operation",
        headers = mapOf("Authorization" to TestDashboardAuthenticator.header(DashboardPrincipal(tenant, UUID.randomUUID(), role))),
    )

    private fun ok(response: HttpResponse<String>): JsonNode {
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200)
        return DashboardHttp.json(response)
    }

    private fun keys(node: JsonNode) = node.propertyNames().asSequence().toSet()

    private fun results(body: JsonNode) = body.path("results").toList().map {
        listOf(it.path("targetId").asString(), it.path("status").asString(), it.path("reason").takeUnless(JsonNode::isNull)?.asString(),
            it.path("action").takeUnless(JsonNode::isNull)?.asString())
    }

    @Test fun `대기 중인 작업은 화면의 작업 응답 키와 Retry-After를 낸다`() {
        val (tenant, admin) = organization()
        val created = store().create(tenant, OperationKind.INSTALLATION_NOTIFICATION, admin, listOf("install-2", "install-1"))

        val response = get(tenant, created.id)
        val body = ok(response)
        assertThat(response.headers().firstValue("Retry-After")).hasValue("2")
        assertThat(keys(body)).containsExactlyInAnyOrder(
            "operationId", "kind", "status", "createdAt", "completedAt", "results", "canRestore", "restoreUntil", "retention")
        assertThat(body.path("results").toList()).hasSize(2).allSatisfy { assertThat(keys(it)).containsExactlyInAnyOrder("targetId", "status", "reason", "action") }
        assertThat(body.path("operationId").asString()).isEqualTo(created.id.toString())
        assertThat(body.path("kind").asString()).isEqualTo("installation_notification")
        assertThat(body.path("status").asString()).isEqualTo("pending")
        assertThat(Instant.parse(body.path("createdAt").asString())).isEqualTo(created.createdAt)
        assertThat(body.path("completedAt").isNull).isTrue()
        assertThat(results(body)).containsExactly(listOf("install-2", "pending", null, null), listOf("install-1", "pending", null, null))
        assertThat(body.path("canRestore").isBoolean).isTrue()
        assertThat(body.path("canRestore").asBoolean()).isFalse()
        assertThat(body.path("restoreUntil").isNull).isTrue()
        assertThat(body.path("retention").isNull).isTrue()
    }

    @Test fun `진행 중에는 Retry-After가 있고 부분 실패로 끝나면 대상별 결과와 사유만 남는다`() {
        val (tenant, admin) = organization()
        val store = store()
        val id = store.create(tenant, OperationKind.INSTALLATION_NOTIFICATION, admin, listOf("a", "b")).id
        store.start(tenant, id)
        store.succeed(tenant, id, "a")

        val running = get(tenant, id)
        assertThat(ok(running).path("status").asString()).isEqualTo("running")
        assertThat(running.headers().firstValue("Retry-After")).hasValue("2")
        assertThat(results(ok(running))).containsExactly(listOf("a", "succeeded", null, null), listOf("b", "pending", null, null))

        val finished = store.fail(tenant, id, "b", "recipient_rejected")
        val done = get(tenant, id)
        val body = ok(done)
        assertThat(done.headers().firstValue("Retry-After")).isEmpty
        assertThat(body.path("status").asString()).isEqualTo("partially_failed")
        assertThat(Instant.parse(body.path("completedAt").asString())).isEqualTo(finished.completedAt)
        assertThat(results(body)).containsExactly(listOf("a", "succeeded", null, null), listOf("b", "failed", "recipient_rejected", null))
    }

    @Test fun `전부 실패한 작업은 404가 아니라 failed 상태로 조회된다`() {
        val (tenant, admin) = organization()
        val store = store()
        val id = store.create(tenant, OperationKind.INSTALLATION_NOTIFICATION, admin, listOf("a")).id
        store.abort(tenant, id, "mail_disabled")
        val response = get(tenant, id)
        val body = ok(response)
        assertThat(response.headers().firstValue("Retry-After")).isEmpty
        assertThat(body.path("status").asString()).isEqualTo("failed")
        assertThat(body.path("completedAt").isNull).isFalse()
        assertThat(results(body)).containsExactly(listOf("a", "failed", "mail_disabled", null))
    }

    @Test fun `조치 대기는 Retry-After 없이 해야 할 조치를 알리고 확인 뒤에 성공이 된다`() {
        val (tenant, admin) = organization()
        val store = store()
        val id = store.create(tenant, OperationKind.SEAT_RECLAIM, admin, listOf("seat-1", "seat-2")).id
        store.start(tenant, id)
        store.succeed(tenant, id, "seat-1")
        store.awaitAdminAction(tenant, id, "seat-2", "revoke_in_vendor_console")

        val waiting = get(tenant, id)
        val body = ok(waiting)
        assertThat(waiting.headers().firstValue("Retry-After")).isEmpty
        assertThat(body.path("status").asString()).isEqualTo("awaiting_admin_action")
        assertThat(body.path("completedAt").isNull).isTrue()
        assertThat(results(body)).containsExactly(
            listOf("seat-1", "succeeded", null, null), listOf("seat-2", "awaiting_admin_action", null, "revoke_in_vendor_console"))
        // 끝나지 않은 회수는 되돌릴 수 없다.
        assertThat(body.path("canRestore").asBoolean()).isFalse()

        store.confirm(tenant, id, "seat-2", admin)
        val done = ok(get(tenant, id))
        assertThat(done.path("status").asString()).isEqualTo("succeeded")
        assertThat(results(done)).containsExactly(
            listOf("seat-1", "succeeded", null, null), listOf("seat-2", "succeeded", null, "revoke_in_vendor_console"))
    }

    @Test fun `복원 가능 여부는 기한과 진행 중인 복원을 반영한다`() {
        val (tenant, admin) = organization()
        val store = store()
        val until = Instant.now().plus(Duration.ofHours(1))
        val reclaim = store.create(tenant, OperationKind.SEAT_RECLAIM, admin, listOf("seat-1")).id
        store.start(tenant, reclaim)
        store.allowRestore(tenant, reclaim, until)
        store.succeed(tenant, reclaim, "seat-1")

        val open = ok(get(tenant, reclaim))
        assertThat(open.path("canRestore").asBoolean()).isTrue()
        assertThat(Instant.parse(open.path("restoreUntil").asString())).isEqualTo(store.find(tenant, reclaim)!!.restoreUntil)

        val restore = store.create(tenant, OperationKind.SEAT_RESTORE, admin, listOf("seat-1"), restores = reclaim).id
        assertThat(ok(get(tenant, reclaim)).path("canRestore").asBoolean()).isFalse()
        val restoring = ok(get(tenant, restore))
        assertThat(restoring.path("kind").asString()).isEqualTo("seat_restore")
        assertThat(restoring.path("canRestore").asBoolean()).isFalse()

        // 기한이 지난 회수: 이틀 전에 끝났고 기한은 하루 전이었다.
        val past = store(Clock.fixed(Instant.now().minus(Duration.ofDays(2)), ZoneOffset.UTC))
        val expired = past.create(tenant, OperationKind.SEAT_RECLAIM, admin, listOf("seat-9")).id
        past.start(tenant, expired)
        past.allowRestore(tenant, expired, Instant.now().minus(Duration.ofDays(1)))
        past.succeed(tenant, expired, "seat-9")
        val late = ok(get(tenant, expired))
        assertThat(late.path("status").asString()).isEqualTo("succeeded")
        assertThat(late.path("canRestore").asBoolean()).isFalse()
        assertThat(late.path("restoreUntil").isNull).isFalse()
    }

    @Test fun `보존 정리 작업은 가장 최근 삭제 실행의 상태를 함께 낸다`() {
        val (tenant, admin) = organization()
        val store = store()
        val id = store.create(tenant, OperationKind.RETENTION_CLEANUP, admin, listOf("analysis_source")).id
        store.start(tenant, id)
        assertThat(ok(get(tenant, id)).path("retention").isNull).isTrue()

        val before = Instant.parse("2025-10-01T15:00:00Z")
        val started = Instant.parse("2026-09-30T01:00:00Z")
        val run = retention.start(tenant, 12, Instant.parse("2026-09-30T00:00:00Z"), before, started)
        store.attachRetention(tenant, id, run)
        val running = ok(get(tenant, id)).path("retention")
        assertThat(keys(running)).containsExactlyInAnyOrder("status", "requestedBefore", "deletedBefore", "startedAt", "finishedAt")
        assertThat(running.path("status").asString()).isEqualTo("running")
        assertThat(Instant.parse(running.path("requestedBefore").asString())).isEqualTo(before)
        assertThat(Instant.parse(running.path("startedAt").asString())).isEqualTo(started)
        assertThat(running.path("deletedBefore").isNull).isTrue()
        assertThat(running.path("finishedAt").isNull).isTrue()

        val finished = Instant.parse("2026-09-30T01:05:00Z")
        retention.boundaryApplied(run, before, 1)
        retention.logicallyDeleted(run, RetentionDeletionCounts(3, 5, 0, 0), finished)
        val deleted = ok(get(tenant, id)).path("retention")
        assertThat(deleted.path("status").asString()).isEqualTo("logically_deleted")
        assertThat(Instant.parse(deleted.path("deletedBefore").asString())).isEqualTo(before)
        assertThat(Instant.parse(deleted.path("finishedAt").asString())).isEqualTo(finished)
        // 삭제 실행 기록의 내부 수치와 상세는 싣지 않는다.
        assertThat(get(tenant, id).body()).doesNotContain("eventRows", "detail", "policyEpoch")
    }

    @Test fun `다른 조직의 삭제 실행 기록은 싣지 않는다`() {
        val (tenant, admin) = organization()
        val (other, _) = organization()
        val store = store()
        val id = store.create(tenant, OperationKind.RETENTION_CLEANUP, admin, listOf("analysis_source")).id
        store.start(tenant, id)
        val foreign = retention.start(other, 12, Instant.parse("2026-09-30T00:00:00Z"), Instant.parse("2025-10-01T15:00:00Z"), Instant.parse("2026-09-30T01:00:00Z"))
        store.attachRetention(tenant, id, foreign)
        assertThat(ok(get(tenant, id)).path("retention").isNull).isTrue()
    }

    @Test fun `다른 조직의 작업과 없는 작업은 같은 404이고 권한은 요청마다 검사한다`() {
        val (tenant, admin) = organization()
        val (other, outsider) = organization()
        val mine = store().create(tenant, OperationKind.INSTALLATION_NOTIFICATION, admin, listOf("a")).id
        val theirs = store().create(other, OperationKind.INSTALLATION_NOTIFICATION, outsider, listOf("secret-target")).id

        val foreign = get(tenant, theirs)
        val missing = get(tenant, UUID.randomUUID())
        for (response in listOf(foreign, missing, get(tenant, "not-a-uuid"))) {
            assertThat(response.statusCode()).isEqualTo(404)
            assertThat(DashboardHttp.json(response).path("error").path("code").asString()).isEqualTo("not_found")
            assertThat(response.headers().firstValue("Retry-After")).isEmpty
            assertThat(response.body()).doesNotContain("secret-target")
        }
        assertThat(DashboardHttp.json(foreign).path("error")).isEqualTo(DashboardHttp.json(missing).path("error"))

        // 경로의 조직이 주체의 조직이 아니면 작업을 보기 전에 거부한다.
        assertThat(get(tenant, theirs, target = other).statusCode()).isEqualTo(403)
        assertThat(get(tenant, mine, role = Role.MEMBER).statusCode()).isEqualTo(403)
        assertThat(get(tenant, mine, role = Role.LEAD).statusCode()).isEqualTo(403)
        assertThat(http.send("/api/v1/organizations/$tenant/operations/$mine").statusCode()).isEqualTo(401)
        assertThat(get(tenant, mine).statusCode()).isEqualTo(200)
    }

    @Test fun `조회는 작업을 바꾸지 않고 GET만 받는다`() {
        val (tenant, admin) = organization()
        val store = store()
        val id = store.create(tenant, OperationKind.INSTALLATION_NOTIFICATION, admin, listOf("a")).id
        val before = store.find(tenant, id)
        repeat(3) { ok(get(tenant, id)) }
        assertThat(store.find(tenant, id)).isEqualTo(before)
        val post = http.send("/api/v1/organizations/$tenant/operations/$id", method = "POST",
            headers = mapOf("Authorization" to TestDashboardAuthenticator.header(DashboardPrincipal(tenant, UUID.randomUUID(), Role.ADMIN))))
        assertThat(post.statusCode()).isEqualTo(405)
        assertThat(store.find(tenant, id)).isEqualTo(before)
    }
}
