package com.team376.pulsemetry.retention

import com.team376.pulsemetry.persistence.telemetry.InsertDrain
import com.team376.pulsemetry.persistence.telemetry.RetentionFence
import com.team376.pulsemetry.persistence.telemetry.RetentionPurge
import com.team376.pulsemetry.persistence.telemetryops.RetentionDeletionCounts
import com.team376.pulsemetry.persistence.telemetryops.RetentionOperationStatus
import com.team376.pulsemetry.persistence.telemetryops.RetentionOperationStore
import com.team376.pulsemetry.persistence.telemetryops.TenantRetentionBoundaryStore
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 조직 하나의 보존 삭제 — ADR 0024 §4 의 순서 그대로다. **어느 단계든 근거가 없으면 완료를 기록하지 않는다.** 같은 입력으로 다시 실행하면
 * 이어서 끝난다 — 경계 발효는 MAX 라 no-op, fence 는 같은 값, DELETE 는 멱등이다.
 *
 * 1. 경계 발효(`advance`) — 커밋되는 순간부터 ingest 의 sink 직전 검사와 새 snapshot build 가 새 경계를 쓰고, 기존 snapshot 은 epoch 비교로
 *    더는 공개되지도 읽히지도 않는다(ADR 0023 §4). 이 작업은 `dashboard_cache` 에 쓰지 않는다.
 * 2. fence 기록 — RDS 에 커밋된 값 그대로.
 * 3. drain — fence 가 반환된 뒤 실행 중이던 INSERT 가 모두 끝날 때까지. 상한을 넘기면 `incomplete`.
 * 4. DELETE — 직전에 삭제 대상을 센다(관측 고유 수·물리 revision 행 수).
 * 5. 검증 — 경계 이전 행이 0 이면 `logically_deleted`. 남았으면 3 부터 다시(최대 [Settings.maxPasses] 회).
 *
 * 수신 ledger·tenant 생애 요약은 건드리지 않는다.
 */
class RetentionJob(
	private val boundaries: TenantRetentionBoundaryStore,
	private val operations: RetentionOperationStore,
	private val fence: RetentionFence,
	private val drain: InsertDrain,
	private val purge: RetentionPurge,
	private val settings: Settings,
	private val clock: Clock,
) {

	data class Settings(val drainTimeout: Duration, val drainPollInterval: Duration, val maxPasses: Int) {
		init {
			require(maxPasses >= 1) { "maxPasses 는 1 이상이다: $maxPasses" }
		}
	}

	data class Outcome(val operationId: UUID, val status: RetentionOperationStatus)

	/**
	 * 실행 기록을 열고 순서대로 진행한다. 기록을 열지 못하면 예외가 그대로 나간다 — 기록 없이 지우지 않는다. 연 뒤의 실패는 `failed` 로 남기고
	 * 돌려준다(남기는 것마저 실패하면 로그만).
	 */
	fun run(command: RetentionCommand): Outcome {
		val requested = command.requestedBefore
		val operationId = operations.start(command.tenantId, command.retentionMonths, command.asOf, requested, clock.instant())
		log.info("retention {} started: tenant {}, {} months as of {} → before {}", operationId, command.tenantId, command.retentionMonths, command.asOf, requested)
		return try {
			execute(operationId, command.tenantId, requested)
		} catch (exception: Exception) {
			log.error("retention {} failed", operationId, exception)
			runCatching { operations.failed(operationId, "${exception.javaClass.simpleName}: ${exception.message}".take(MAX_DETAIL), clock.instant()) }
				.onFailure { log.error("retention {} could not record the failure", operationId, it) }
			Outcome(operationId, RetentionOperationStatus.FAILED)
		}
	}

	private fun execute(operationId: UUID, tenantId: UUID, requested: Instant): Outcome {
		// 1. 경계 발효. 이미 더 늦은 경계가 있으면 그 값이 DELETE 조건이다.
		val applied = boundaries.advance(tenantId, requested).boundary
		val deletedBefore = checkNotNull(applied.deletedBefore) { "발효 뒤에도 경계가 없다" }
		operations.boundaryApplied(operationId, deletedBefore, applied.policyEpoch)

		// 2. fence — 커밋된 값 그대로. 반환된 뒤 새로 등록되는 INSERT 는 이 값으로 판정된다.
		val tenant = tenantId.toString()
		fence.write(tenant, RetentionFence.Value(deletedBefore, applied.policyEpoch))

		var counted: RetentionDeletionCounts? = null
		repeat(settings.maxPasses) { pass ->
			// 3. drain — 목록은 fence 가 반환된 뒤에 읽어야 한다.
			val inFlight = drain.running()
			if (!drain.awaitFinished(inFlight, settings.drainTimeout, settings.drainPollInterval)) {
				val detail = "drain: fence 이전에 등록된 INSERT 가 ${settings.drainTimeout} 안에 끝나지 않았다 (pass ${pass + 1}, ${inFlight.size} 개 중 일부)"
				log.warn("retention {} incomplete — {}", operationId, detail)
				operations.incomplete(operationId, detail, counted, clock.instant())
				return Outcome(operationId, RetentionOperationStatus.INCOMPLETE)
			}

			// 4. DELETE — 직전에 센다.
			counted = (counted ?: RetentionDeletionCounts.NONE) + purge.count(tenant, deletedBefore).asCounts()
			purge.delete(tenant, deletedBefore)

			// 5. 검증.
			val remaining = purge.remaining(tenant, deletedBefore)
			if (remaining == 0L) {
				operations.logicallyDeleted(operationId, counted!!, clock.instant())
				log.info("retention {} logically deleted before {} (epoch {}): {}", operationId, deletedBefore, applied.policyEpoch, counted)
				return Outcome(operationId, RetentionOperationStatus.LOGICALLY_DELETED)
			}
			log.warn("retention {} pass {}: {} rows remain before {}", operationId, pass + 1, remaining, deletedBefore)
		}
		val detail = "DELETE 뒤에도 경계 이전 행이 남았다 (${settings.maxPasses} 회)"
		operations.incomplete(operationId, detail, counted, clock.instant())
		return Outcome(operationId, RetentionOperationStatus.INCOMPLETE)
	}

	private fun RetentionPurge.Targets.asCounts() =
		RetentionDeletionCounts(events.observations, events.rows, metricPoints.observations, metricPoints.rows)

	private companion object {
		private val log = LoggerFactory.getLogger(RetentionJob::class.java)
		private const val MAX_DETAIL = 1_000
	}
}
