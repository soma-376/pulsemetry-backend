package com.team376.pulsemetry.retention

import com.team376.pulsemetry.persistence.enrollment.operation.RetentionCleanupRequests
import com.team376.pulsemetry.persistence.enrollment.operation.RetentionCleanupRequests.RunResult
import com.team376.pulsemetry.persistence.telemetryops.RetentionOperationStatus
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * 요청 모드(ADR 0047) — 조직이 집계 보존을 줄여 남긴 보존 정리 요청을 하나씩 선점해 **기존 보존 삭제([RetentionJob]) 그대로** 실행하고,
 * 결과를 공통 작업에 옮긴다. 삭제 순서(발효 → fence → drain → DELETE → 남은 행 0 확인)는 [RetentionJob] 의 것이고 여기서 바꾸지 않는다.
 *
 * 경계의 입력은 요청의 `as_of`·보존 개월 수다 — 미완·실패로 끝나 다시 실행해도 같은 경계다. 한 번 실행에서 다룬 요청은 다시 잡지 않는다
 * (다음 실행이 이어서 끝낸다). [maxRuns] 번째 실행에서도 끝내지 못하면 작업을 실패로 닫는다.
 */
class RetentionRequestProcessor(
	private val requests: RetentionCleanupRequests,
	private val job: RetentionJob,
	private val clock: Clock,
	/** 선점 기한. 한 요청의 삭제 실행 전체보다 길어야 한다. */
	private val lease: Duration?,
	/** 한 요청을 몇 번까지 실행하는가. */
	private val maxRuns: Int?,
	private val worker: String = UUID.randomUUID().toString(),
) {

	fun runAll(): Int {
		if (lease == null || maxRuns == null) {
			log.error("retention requests rejected: pulsemetry.retention.requests.lease and max-runs must be set")
			return RetentionCommandRunner.EXIT_USAGE
		}
		val handled = mutableSetOf<UUID>()
		var failed = false
		var incomplete = false
		while (true) {
			val request = requests.claim(worker, clock.instant(), lease, handled) ?: break
			handled += request.operationId
			val (runId, result) = try {
				job.run(RetentionCommand(request.tenantId, request.retentionMonths, request.asOf)).let { outcome ->
					outcome.operationId to when (outcome.status) {
						RetentionOperationStatus.LOGICALLY_DELETED -> RunResult.LOGICALLY_DELETED
						RetentionOperationStatus.INCOMPLETE -> RunResult.INCOMPLETE
						RetentionOperationStatus.FAILED, RetentionOperationStatus.RUNNING -> RunResult.FAILED
					}
				}
			} catch (exception: Exception) {
				// 삭제 실행 기록조차 열지 못했다 — 지운 것이 없다.
				log.error("retention request {} could not start: tenant {}", request.operationId, request.tenantId, exception)
				null to RunResult.FAILED
			}
			val giveUp = request.runs >= maxRuns
			val recorded = try {
				requests.finish(request, worker, runId, result, giveUp, clock.instant())
			} catch (exception: Exception) {
				log.error("retention request {} could not record run {}", request.operationId, runId, exception)
				false
			}
			if (!recorded) {
				log.warn("retention request {} was not recorded (claim lost or record failed) — the next run finishes it", request.operationId)
				incomplete = true
				continue
			}
			log.info("retention request {} ({} months as of {}, run {} of {}) → {} {}{}", request.operationId, request.retentionMonths, request.asOf,
				request.runs, maxRuns, runId, result, if (giveUp && result != RunResult.LOGICALLY_DELETED) " — gave up" else "")
			when (result) {
				RunResult.LOGICALLY_DELETED -> Unit
				RunResult.INCOMPLETE -> incomplete = true
				RunResult.FAILED -> failed = true
			}
		}
		log.info("retention requests handled: {}", handled.size)
		return when {
			failed -> RetentionCommandRunner.EXIT_FAILED
			incomplete -> RetentionCommandRunner.EXIT_INCOMPLETE
			else -> RetentionCommandRunner.EXIT_DELETED
		}
	}

	private companion object {
		private val log = LoggerFactory.getLogger(RetentionRequestProcessor::class.java)
	}
}
