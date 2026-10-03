package com.team376.pulsemetry.retention

import com.team376.pulsemetry.persistence.enrollment.operation.OperationStatus
import com.team376.pulsemetry.persistence.enrollment.operation.OperationStore
import com.team376.pulsemetry.persistence.enrollment.operation.OperationTargetStatus
import com.team376.pulsemetry.persistence.enrollment.operation.RetentionCleanupRequests
import com.team376.pulsemetry.persistence.telemetry.ClickHouseHttpClient
import com.team376.pulsemetry.persistence.telemetry.InsertDrain
import com.team376.pulsemetry.persistence.telemetry.RetentionFence
import com.team376.pulsemetry.persistence.telemetry.RetentionPurge
import com.team376.pulsemetry.persistence.telemetryops.RetentionOperationStatus
import com.team376.pulsemetry.persistence.telemetryops.RetentionOperationStore
import com.team376.pulsemetry.persistence.telemetryops.TenantRetentionBoundaryStore
import com.team376.pulsemetry.retention.RetentionTestStores.Row
import com.team376.pulsemetry.retention.RetentionTestStores.observationId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * 요청 모드(ADR 0047) — 조직이 집계 보존을 줄여 남긴 요청을 기존 보존 삭제(ADR 0024 §4) 그대로 실행하고 결과를 공통 작업에 옮긴다.
 * 기대값은 ADR 의 규칙에서 쓴다: 경계 = 요청 시각의 KST 날짜에서 N 개월 전 자정, 논리 삭제 완료 → 작업 성공, 미완·실패 → 작업은 진행 중이고
 * 다음 실행이 같은 입력으로 이어서 끝낸다(정한 횟수를 넘으면 실패로 닫는다), 조직마다 오래된 요청부터 하나씩, 두 실행이 같은 요청을 잡지 않는다.
 */
class RetentionRequestModeTest {

	private val stores = RetentionTestStores
	private val client = stores.client
	private val jdbc = JdbcClient.create(stores.dataSource)
	private val manager = DataSourceTransactionManager(stores.dataSource)
	private val clock = Clock.fixed(Instant.parse("2026-09-24T01:30:00Z"), ZoneOffset.UTC)
	private val operations = OperationStore(jdbc, manager, clock)
	private val requests = RetentionCleanupRequests(jdbc, manager, operations)
	private val runs = RetentionOperationStore(stores.dataSource)
	private val boundaries = TenantRetentionBoundaryStore(stores.dataSource)

	/** 요청 시각 2026-09-24 10:00 KST, 12개월 → 경계 2025-09-24 00:00 KST. */
	private val asOf = Instant.parse("2026-09-24T01:00:00Z")
	private val boundary = Instant.parse("2025-09-23T15:00:00Z")

	@BeforeEach
	fun setUp() {
		stores.clear()
		jdbc.sql("TRUNCATE enrollment.retention_cleanup_requests, enrollment.operation_targets, enrollment.operations CASCADE").update()
	}

	private data class Org(val tenant: UUID, val admin: UUID)

	private fun organization(): Org {
		val tenant = UUID.randomUUID()
		val admin = UUID.randomUUID()
		jdbc.sql("INSERT INTO enrollment.tenants(id,name,slug) VALUES (:id,'보존 테스트',:slug)").param("id", tenant).param("slug", "retention-$tenant").update()
		jdbc.sql("INSERT INTO enrollment.members(id,tenant_id,email,role) VALUES (:id,:tenant,:email,'admin')")
			.param("id", admin).param("tenant", tenant).param("email", "admin-$admin@example.test").update()
		return Org(tenant, admin)
	}

	/** 저장 명령이 하는 일 — 무기한에서 [months] 로 줄였다. */
	private fun request(org: Org, months: Int = 12, at: Instant = asOf): UUID = requireNotNull(requests.onRetentionChanged(org.tenant, org.admin, null, months, at))

	private fun job(drainTimeout: Duration = Duration.ofSeconds(10), clickHouse: ClickHouseHttpClient = client) = RetentionJob(
		boundaries = boundaries, operations = runs, fence = RetentionFence(clickHouse), drain = InsertDrain(clickHouse), purge = RetentionPurge(clickHouse),
		settings = RetentionJob.Settings(drainTimeout, Duration.ofMillis(50), maxPasses = 2), clock = clock,
	)

	private fun processor(job: RetentionJob = job(), maxRuns: Int = 3, worker: String = "worker-${UUID.randomUUID()}") =
		RetentionRequestProcessor(requests, job, clock, Duration.ofMinutes(10), maxRuns, worker)

	private fun operation(org: Org, id: UUID) = requireNotNull(operations.find(org.tenant, id))
	private fun row(id: UUID) = requireNotNull(requests.find(id))

	@Test
	@DisplayName("요청을 선점해 기존 보존 삭제로 실행하고, 논리 삭제 완료면 작업을 성공으로 닫고 그 삭제 실행을 가리킨다")
	fun completes() {
		val org = organization()
		val other = organization()
		stores.insert("telemetry_events", listOf(
			Row(org.tenant, observationId(1), boundary.minusNanos(1)), Row(org.tenant, observationId(2), boundary),
			Row(other.tenant, observationId(3), Instant.parse("2024-01-01T00:00:00Z")),
		))
		val id = request(org)
		assertThat(operation(org, id).status).isEqualTo(OperationStatus.PENDING)

		assertThat(processor().runAll()).isEqualTo(RetentionCommandRunner.EXIT_DELETED)

		val done = operation(org, id)
		assertThat(done.status).isEqualTo(OperationStatus.SUCCEEDED)
		assertThat(done.targets.map { it.targetId to it.status }).containsExactly(RetentionCleanupRequests.TARGET to OperationTargetStatus.SUCCEEDED)
		val run = requireNotNull(runs.find(requireNotNull(done.retentionOperationId)))
		assertThat(listOf(run.tenantId, run.retentionMonths, run.asOf, run.deletedBefore, run.status))
			.containsExactly(org.tenant, 12, asOf, boundary, RetentionOperationStatus.LOGICALLY_DELETED)
		assertThat(stores.ids("telemetry_events", org.tenant)).containsExactly(observationId(2))
		assertThat(stores.rows("telemetry_events", other.tenant)).isEqualTo(1)
		assertThat(row(id)).containsEntry("outcome", "logically_deleted").containsEntry("runs", 1).containsEntry("claimed_by", null)
		// 끝난 요청은 다시 잡히지 않는다.
		assertThat(processor().runAll()).isEqualTo(RetentionCommandRunner.EXIT_DELETED)
		assertThat(row(id)["runs"]).isEqualTo(1)
	}

	@Test
	@DisplayName("drain 이 끝나지 않으면 작업은 진행 중이고 삭제 실행은 미완으로 보인다 — 다음 실행이 같은 경계로 이어서 끝낸다")
	fun incompleteThenFinishes() {
		val org = organization()
		stores.insert("telemetry_events", listOf(Row(org.tenant, observationId(1), boundary.minusSeconds(1))))
		val id = request(org)
		val queryId = "stale-writer-${UUID.randomUUID()}"
		RetentionTestStores.StaleWriter(org.tenant, queryId).use { writer ->
			// 첫 조각이 max_query_size 보다 커야 서버에 등록된다(RetentionTestStores.StaleWriter).
			writer.send((100 until 300).map { Row(org.tenant, observationId(it), Instant.parse("2025-08-01T00:00:00Z")) })
			assertThat(stores.registered(queryId)).isTrue()

			// 한 번의 실행에서 미완인 요청을 곧바로 되풀이하지 않는다.
			assertThat(processor(job(drainTimeout = Duration.ofMillis(300))).runAll()).isEqualTo(RetentionCommandRunner.EXIT_INCOMPLETE)

			val running = operation(org, id)
			assertThat(running.status).isEqualTo(OperationStatus.RUNNING)
			assertThat(runs.find(requireNotNull(running.retentionOperationId))!!.status).isEqualTo(RetentionOperationStatus.INCOMPLETE)
			assertThat(row(id)).containsEntry("runs", 1).containsEntry("claimed_by", null).containsEntry("closed_at", null)
			assertThat(writer.finish()).isEqualTo(200)
		}
		val first = operation(org, id).retentionOperationId

		assertThat(processor().runAll()).isEqualTo(RetentionCommandRunner.EXIT_DELETED)

		val done = operation(org, id)
		assertThat(done.status).isEqualTo(OperationStatus.SUCCEEDED)
		assertThat(done.retentionOperationId).isNotEqualTo(first)
		assertThat(runs.find(done.retentionOperationId!!)!!.deletedBefore).isEqualTo(boundary)
		assertThat(stores.rows("telemetry_events", org.tenant, before = boundary)).isZero()
		assertThat(row(id)["runs"]).isEqualTo(2)
	}

	@Test
	@DisplayName("실패한 실행은 다음 실행이 이어서 끝내고, 정한 횟수 안에 끝내지 못하면 작업을 실패로 닫는다")
	fun failures() {
		val retried = organization()
		val retriedId = request(retried)
		val unreachable = ClickHouseHttpClient("http://127.0.0.1:1")
		assertThat(processor(job(clickHouse = unreachable), maxRuns = 2).runAll()).isEqualTo(RetentionCommandRunner.EXIT_FAILED)
		assertThat(operation(retried, retriedId).status).isEqualTo(OperationStatus.RUNNING)
		assertThat(runs.find(operation(retried, retriedId).retentionOperationId!!)!!.status).isEqualTo(RetentionOperationStatus.FAILED)
		assertThat(processor(maxRuns = 2).runAll()).isEqualTo(RetentionCommandRunner.EXIT_DELETED)
		assertThat(operation(retried, retriedId).status).isEqualTo(OperationStatus.SUCCEEDED)

		val given = organization()
		val givenId = request(given)
		repeat(2) { assertThat(processor(job(clickHouse = unreachable), maxRuns = 2).runAll()).isEqualTo(RetentionCommandRunner.EXIT_FAILED) }
		val failed = operation(given, givenId)
		assertThat(failed.status).isEqualTo(OperationStatus.FAILED)
		assertThat(failed.targets.single().reason).isEqualTo(RetentionCleanupRequests.RETENTION_FAILED)
		assertThat(row(givenId)).containsEntry("outcome", "failed").containsEntry("runs", 2)
		// 닫힌 요청은 더 실행하지 않는다.
		assertThat(processor(maxRuns = 2).runAll()).isEqualTo(RetentionCommandRunner.EXIT_DELETED)
		assertThat(operation(given, givenId).status).isEqualTo(OperationStatus.FAILED)
	}

	@Test
	@DisplayName("조직마다 오래된 요청부터 하나씩 잡고, 선점 기한 안에는 다른 실행이 잡지 못한다")
	fun claimOrder() {
		val a = organization()
		val b = organization()
		val older = request(a, at = asOf)
		// 첫 요청이 실행을 시작했다 — 그 뒤의 저장은 대체가 아니라 새 요청이다.
		val first = requireNotNull(requests.claim("w1", clock.instant(), Duration.ofMinutes(10)))
		assertThat(first.operationId).isEqualTo(older)
		val newer = requireNotNull(requests.onRetentionChanged(a.tenant, a.admin, 12, 6, asOf.plusSeconds(60)))
		val otherOrg = request(b, at = asOf.plusSeconds(120))

		// w1 이 잡은 A 의 오래된 요청이 열려 있는 동안 A 의 새 요청은 잡히지 않는다. B 는 따로 잡힌다.
		assertThat(requests.claim("w2", clock.instant(), Duration.ofMinutes(10))?.operationId).isEqualTo(otherOrg)
		assertThat(requests.claim("w3", clock.instant(), Duration.ofMinutes(10))).isNull()
		// 기한이 지나면 같은 요청을 다시 잡는다(순서는 그대로).
		assertThat(requests.claim("w4", clock.instant().plus(Duration.ofMinutes(11)), Duration.ofMinutes(10))?.operationId).isEqualTo(older)
		assertThat(requests.find(newer)!!["runs"]).isEqualTo(0)
		// 선점을 잃은 실행은 결과를 쓰지 않는다.
		assertThat(requests.finish(first, "w1", null, RetentionCleanupRequests.RunResult.FAILED, giveUp = true, clock.instant())).isFalse()
		assertThat(operation(a, older).status).isEqualTo(OperationStatus.RUNNING)
	}

	@Test
	@DisplayName("동시에 도는 두 실행은 같은 요청을 잡지 않는다")
	fun concurrentClaims() {
		repeat(5) { round ->
			jdbc.sql("TRUNCATE enrollment.retention_cleanup_requests, enrollment.operation_targets, enrollment.operations CASCADE").update()
			request(organization())
			val gate = CountDownLatch(1)
			Executors.newFixedThreadPool(2).use { pool ->
				val claims = (1..2).map { worker -> pool.submit(Callable { gate.await(); requests.claim("w$round-$worker", clock.instant(), Duration.ofMinutes(10)) }) }
				gate.countDown()
				assertThat(claims.map { it.get() }.count { it != null }).describedAs("round $round").isEqualTo(1)
			}
		}
	}
}
