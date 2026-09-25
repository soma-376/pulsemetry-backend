package com.team376.pulsemetry.retention

import com.team376.pulsemetry.persistence.telemetry.ClickHouseHttpClient
import com.team376.pulsemetry.persistence.telemetry.InsertDrain
import com.team376.pulsemetry.persistence.telemetry.RetentionFence
import com.team376.pulsemetry.persistence.telemetry.RetentionPurge
import com.team376.pulsemetry.persistence.telemetryops.RetentionDeletionCounts
import com.team376.pulsemetry.persistence.telemetryops.RetentionOperationStatus
import com.team376.pulsemetry.persistence.telemetryops.RetentionOperationStore
import com.team376.pulsemetry.persistence.telemetryops.SummaryOrigin
import com.team376.pulsemetry.persistence.telemetryops.TenantIngestSummaryStore
import com.team376.pulsemetry.persistence.telemetryops.TenantRetentionBoundary
import com.team376.pulsemetry.persistence.telemetryops.TenantRetentionBoundaryStore
import com.team376.pulsemetry.retention.RetentionTestStores.Row
import com.team376.pulsemetry.retention.RetentionTestStores.observationId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * 보존 작업의 순서와 완료 판정(ADR 0024 §4)을 실제 PostgreSQL·ClickHouse 위에서 본다. 기대값은 사례 8·27 과 허브 ADR 0007 AC7·AC8·AC10
 * 에서 온다 — 구 경계로 등록된 INSERT 가 끝나기 전에는 완료가 없고, 끝난 뒤의 실행은 그 INSERT 가 남긴 행까지 지운다. 수신 ledger 와
 * tenant 생애 요약은 남는다.
 */
class RetentionJobTest {

	private val stores = RetentionTestStores
	private val client = stores.client
	private val boundaries = TenantRetentionBoundaryStore(stores.dataSource)
	private val operations = RetentionOperationStore(stores.dataSource)
	private val summaries = TenantIngestSummaryStore(stores.dataSource)
	private val clock = Clock.fixed(Instant.parse("2026-09-24T01:30:00Z"), ZoneOffset.UTC)

	private val tenantA: UUID = UUID.randomUUID()
	private val tenantB: UUID = UUID.randomUUID()

	/** 사례 8 — asOf 2026-09-24 KST, 12개월. */
	private val command = RetentionCommand(tenantA, 12, Instant.parse("2026-09-24T01:00:00Z"))
	private val boundary = Instant.parse("2025-09-23T15:00:00Z")

	@BeforeEach
	fun setUp() {
		stores.clear()
	}

	private fun job(drainTimeout: Duration = Duration.ofSeconds(10), clickHouse: ClickHouseHttpClient = client) = RetentionJob(
		boundaries = boundaries,
		operations = operations,
		fence = RetentionFence(clickHouse),
		drain = InsertDrain(clickHouse),
		purge = RetentionPurge(clickHouse),
		settings = RetentionJob.Settings(drainTimeout, Duration.ofMillis(50), maxPasses = 3),
		clock = clock,
	)

	@Test
	@DisplayName("사례 8 — 경계 이전 모든 revision 을 지우고 경계 시각·이후는 남는다. 같은 월 파티션의 조직 B, ledger·생애 요약도 남는다")
	fun case8() {
		stores.insert(
			"telemetry_events",
			listOf(
				Row(tenantA, observationId(1), boundary.minusNanos(1), rowVersion = 1),
				Row(tenantA, observationId(1), boundary.minusNanos(1), rowVersion = 2),
				Row(tenantA, observationId(2), Instant.parse("2025-09-01T00:00:00Z")),
				Row(tenantA, observationId(3), boundary),
				Row(tenantA, observationId(4), Instant.parse("2026-01-01T00:00:00Z")),
				Row(tenantB, observationId(5), Instant.parse("2025-09-10T00:00:00Z")),
				Row(tenantB, observationId(6), Instant.parse("2024-01-01T00:00:00Z")),
			),
		)
		stores.insert(
			"telemetry_metric_points",
			listOf(Row(tenantA, observationId(7), boundary.minusSeconds(60)), Row(tenantA, observationId(8), boundary.plusSeconds(60))),
		)
		stores.insertLedger(tenantA, Instant.parse("2025-09-01T00:00:00Z"))
		summaries.record(tenantA, SummaryOrigin.LIVE, Instant.parse("2025-01-02T00:00:00Z"), Instant.parse("2025-01-01T00:00:00Z"))
		val summaryBefore = summaries.find(tenantA)

		val outcome = job().run(command)

		assertThat(outcome.status).isEqualTo(RetentionOperationStatus.LOGICALLY_DELETED)
		assertThat(stores.rows("telemetry_events", tenantA, before = boundary)).isZero()
		assertThat(stores.rows("telemetry_metric_points", tenantA, before = boundary)).isZero()
		assertThat(stores.ids("telemetry_events", tenantA)).containsExactly(observationId(3), observationId(4))
		assertThat(stores.ids("telemetry_metric_points", tenantA)).containsExactly(observationId(8))
		assertThat(stores.ids("telemetry_events", tenantB)).containsExactly(observationId(5), observationId(6))
		// AC7 — ledger 와 생애 요약은 보존 삭제의 대상이 아니다.
		assertThat(stores.rows("telemetry_ingest_ledger", tenantA)).isEqualTo(1)
		assertThat(summaries.find(tenantA)).isEqualTo(summaryBefore)

		val operation = operations.find(outcome.operationId)!!
		assertThat(operation.deletedBefore).isEqualTo(boundary)
		assertThat(operation.policyEpoch).isEqualTo(1)
		// 관측 1 은 revision 둘 — 관측 수와 행 수가 다르다.
		assertThat(operation.counts).isEqualTo(RetentionDeletionCounts(eventObservations = 2, eventRows = 3, metricPointObservations = 1, metricPointRows = 1))
		assertThat(operation.finishedAt).isEqualTo(clock.instant())
		// 경계가 발효되고 fence 가 같은 값이다 — 기존 snapshot 은 epoch 비교로 무효다(ADR 0023 §4).
		assertThat(boundaries.read(tenantA)).isEqualTo(TenantRetentionBoundary(tenantA, boundary, 1))
		assertThat(RetentionFence(client).current(tenantA.toString())).isEqualTo(RetentionFence.Value(boundary, 1))
		assertThat(boundaries.read(tenantB)).isEqualTo(TenantRetentionBoundary.none(tenantB))
	}

	@Test
	@DisplayName("사례 27 — 구 경계로 등록된 INSERT 가 끝나기 전에는 완료가 없다. 끝난 뒤 재실행이 그 행까지 지우고, 이후 재전송은 되살리지 못한다")
	fun case27() {
		stores.insert("telemetry_events", listOf(Row(tenantA, observationId(1), Instant.parse("2025-09-01T00:00:00Z")), Row(tenantA, observationId(2), boundary)))
		val queryId = "stale-writer-${UUID.randomUUID()}"

		RetentionTestStores.StaleWriter(tenantA, queryId).use { writer ->
			// 경계 발효 전에 등록된 writer — fence 가 없을 때 평가를 마쳤다.
			writer.send((100 until 300).map { Row(tenantA, observationId(it), Instant.parse("2025-08-01T00:00:00Z")) })
			assertThat(stores.registered(queryId)).isTrue()

			val first = job(drainTimeout = Duration.ofMillis(300)).run(command)

			assertThat(first.status).isEqualTo(RetentionOperationStatus.INCOMPLETE)
			val incomplete = operations.find(first.operationId)!!
			assertThat(incomplete.detail).contains("drain")
			assertThat(incomplete.counts).isNull()
			// 경계는 이미 발효됐다 — 조회·공개는 새 epoch 를 본다. 삭제는 아직이다.
			assertThat(incomplete.policyEpoch).isEqualTo(1)
			assertThat(stores.rows("telemetry_events", tenantA, before = boundary)).isEqualTo(1)

			// 구 writer 가 fence 가 움직인 뒤 도착한 행까지 보내고 끝난다 — 시작 때의 fence(없음)로 판정되어 남는다.
			writer.send(listOf(Row(tenantA, observationId(300), Instant.parse("2025-08-02T00:00:00Z"))))
			assertThat(writer.finish()).isEqualTo(200)
		}
		assertThat(stores.rows("telemetry_events", tenantA, before = boundary)).isEqualTo(1L + 201L)

		val second = job().run(command)

		assertThat(second.status).isEqualTo(RetentionOperationStatus.LOGICALLY_DELETED)
		assertThat(stores.rows("telemetry_events", tenantA, before = boundary)).isZero()
		assertThat(stores.ids("telemetry_events", tenantA)).containsExactly(observationId(2))
		val completed = operations.find(second.operationId)!!
		assertThat(completed.counts!!.eventObservations).isEqualTo(202)
		// 같은 입력의 재실행은 경계를 다시 옮기지 않는다 — epoch 가 그대로라 새로 만든 snapshot 을 괜히 무효화하지 않는다.
		assertThat(completed.policyEpoch).isEqualTo(1)

		// 이후의 재전송·재처리 — 경계를 읽기 전의 writer 처럼 거르지 않고 보내도 서버의 fence 가 버린다.
		RetentionTestStores.StaleWriter(tenantA, "resend-${UUID.randomUUID()}").use { resend ->
			resend.send(listOf(Row(tenantA, observationId(1), Instant.parse("2025-09-01T00:00:00Z"))))
			assertThat(resend.finish()).isEqualTo(200)
		}
		assertThat(stores.rows("telemetry_events", tenantA, before = boundary)).isZero()
	}

	@Test
	@DisplayName("보존 기간 연장은 복원하지 않는다 — 더 긴 보존으로 다시 실행해도 경계는 그대로이고 그 경계로 지운다")
	fun extensionDoesNotRestore() {
		job().run(command)
		stores.insert("telemetry_events", listOf(Row(tenantA, observationId(1), boundary.minusSeconds(1))))

		val extended = job().run(command.copy(retentionMonths = 36))

		val operation = operations.find(extended.operationId)!!
		assertThat(extended.status).isEqualTo(RetentionOperationStatus.LOGICALLY_DELETED)
		assertThat(operation.requestedBefore).isEqualTo(Instant.parse("2023-09-23T15:00:00Z"))
		assertThat(operation.deletedBefore).isEqualTo(boundary)
		assertThat(operation.policyEpoch).isEqualTo(1)
		assertThat(stores.rows("telemetry_events", tenantA, before = boundary)).isZero()
	}

	@Test
	@DisplayName("ClickHouse 에 닿지 못하면 failed 로 기록한다 — 경계는 발효된 채 남아 계속 집행되고, 재실행이 이어서 끝낸다")
	fun clickHouseFailureIsRecorded() {
		val outcome = job(clickHouse = ClickHouseHttpClient("http://127.0.0.1:1")).run(command)

		assertThat(outcome.status).isEqualTo(RetentionOperationStatus.FAILED)
		val operation = operations.find(outcome.operationId)!!
		assertThat(operation.detail).contains("TelemetrySinkUnavailableException")
		assertThat(operation.counts).isNull()
		assertThat(boundaries.read(tenantA).policyEpoch).isEqualTo(1)

		assertThat(job().run(command).status).isEqualTo(RetentionOperationStatus.LOGICALLY_DELETED)
	}

	@Test
	@DisplayName("지울 것이 없어도 완료다 — 수는 0 이고 경계는 발효된다")
	fun nothingToDelete() {
		val outcome = job().run(command)

		assertThat(outcome.status).isEqualTo(RetentionOperationStatus.LOGICALLY_DELETED)
		val operation = operations.find(outcome.operationId)!!
		assertThat(operation.counts).isEqualTo(RetentionDeletionCounts.NONE)
		assertThat(operation.detail).isNull()
		assertThat(operation.policyEpoch).isEqualTo(1)
	}
}
