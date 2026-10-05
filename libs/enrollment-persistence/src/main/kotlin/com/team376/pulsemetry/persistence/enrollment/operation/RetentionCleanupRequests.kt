package com.team376.pulsemetry.persistence.enrollment.operation

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 보존 정리 요청 (ADR 0047) — `enrollment.retention_cleanup_requests` 의 쓰기. 빈이 아니다(ADR 0011).
 *
 * 조직이 집계 보존을 줄인 저장(수집 정책 저장 명령)이 요청과 `retention_cleanup` 작업을 **같은 트랜잭션에서** 만들고([onRetentionChanged]),
 * 보존 작업의 요청 모드가 하나씩 선점해([claim]) 기존 보존 삭제로 실행한 뒤 결과를 작업에 옮긴다([finish]). 삭제는 여기서 하지 않는다.
 *
 * 삭제 실행의 결과 → 작업: 논리 삭제 완료 → 대상 성공(작업 `succeeded`). 미완·실패 → 작업은 `running` 그대로이고 요청을 풀어 다음 실행이
 * 같은 입력으로 이어서 끝낸다(보존 삭제는 재실행이 이어서 끝낸다 — ADR 0024). 실행하는 쪽이 정한 횟수 안에 끝내지 못하면 대상 실패
 * (`retention_incomplete`·`retention_failed`, 작업 `failed`)로 닫는다. 작업은 언제나 가장 최근 삭제 실행을 가리킨다.
 */
class RetentionCleanupRequests(private val jdbc: JdbcClient, manager: PlatformTransactionManager, private val operations: OperationStore) {
    private val tx = TransactionTemplate(manager)

    data class Request(val operationId: UUID, val tenantId: UUID, val retentionMonths: Int, val asOf: Instant, val runs: Int)

    /** 삭제 실행 한 번의 결과. 보존 작업의 실행 기록 상태(`telemetry_ops.retention_operations`)와 같은 뜻이다. */
    enum class RunResult { LOGICALLY_DELETED, INCOMPLETE, FAILED }

    /**
     * 집계 보존이 [previous] 에서 [next] 로 바뀌었다(null = 무기한). 저장 명령의 트랜잭션 안에서 부른다. 만든 정리 작업의 ID 를 돌려준다.
     * - 아직 한 번도 실행하지 않은 요청이 있으면 대체한다 — 요청은 `superseded` 로 닫고 작업은 같은 사유로 중단한다. 마지막 저장만 실행한다.
     * - [next] 가 유한하고, 보존이 줄었거나(무기한 → 유한 포함) 대체한 요청이 있었으면 새 요청을 만든다.
     *   대체한 요청은 이미 삭제가 필요했던 조직이므로 늘린 값이라도 새 값으로 다시 요청한다.
     * - 그 밖(늘렸고 대체할 요청이 없음, 무기한으로 바꿈)은 요청이 없다. 지운 기록은 되돌리지 않는다(ADR 0024 §7).
     * 이미 실행을 시작한 요청은 대체하지 않는다 — 끝까지 실행된다.
     */
    fun onRetentionChanged(tenantId: UUID, requestedBy: UUID, previous: Int?, next: Int?, now: Instant): UUID? {
        val waiting = jdbc.sql("SELECT operation_id FROM enrollment.retention_cleanup_requests WHERE tenant_id=:tenant AND closed_at IS NULL AND runs=0 FOR UPDATE")
            .param("tenant", tenantId).query(UUID::class.java).optional().orElse(null)
        if (waiting != null) {
            close(waiting, SUPERSEDED, now)
            operations.abort(tenantId, waiting, SUPERSEDED)
        }
        val shortened = next != null && (previous == null || next < previous)
        if (next == null || (waiting == null && !shortened)) return null
        val operation = operations.create(tenantId, OperationKind.RETENTION_CLEANUP, requestedBy, listOf(TARGET))
        jdbc.sql("INSERT INTO enrollment.retention_cleanup_requests(operation_id,tenant_id,retention_months,as_of) VALUES (:id,:tenant,:months,:asOf)")
            .param("id", operation.id).param("tenant", tenantId).param("months", next).param("asOf", Timestamp.from(now)).update()
        return operation.id
    }

    /**
     * 실행할 요청 하나를 [lease] 동안 선점하고 그 작업을 시작한다. 없으면 null.
     * 조직마다 가장 오래된 열린 요청부터, 한 번에 하나만 — 더 오래된 요청이 열려 있으면(다른 실행이 잡고 있거나 이번 실행에서 미완으로 끝났으면) 뒤의 요청을 잡지 않는다.
     * 동시에 도는 두 실행은 `SKIP LOCKED` 로 같은 요청을 잡지 않는다. [exclude] 는 이번 실행에서 이미 다룬 요청이다(미완을 곧바로 다시 잡지 않는다).
     */
    fun claim(worker: String, now: Instant, lease: Duration, exclude: Collection<UUID> = emptyList()): Request? = tx.execute {
        val request = jdbc.sql("""UPDATE enrollment.retention_cleanup_requests r SET runs=r.runs+1, claimed_by=:worker, claimed_until=:until
            WHERE r.operation_id = (
                SELECT c.operation_id FROM enrollment.retention_cleanup_requests c
                WHERE c.closed_at IS NULL AND (c.claimed_until IS NULL OR c.claimed_until <= :now)
                  AND c.operation_id::text <> ALL(string_to_array(:exclude, ','))
                  AND NOT EXISTS (SELECT 1 FROM enrollment.retention_cleanup_requests o WHERE o.tenant_id=c.tenant_id AND o.closed_at IS NULL
                      AND (o.as_of, o.operation_id) < (c.as_of, c.operation_id))
                ORDER BY c.as_of, c.operation_id LIMIT 1 FOR UPDATE SKIP LOCKED)
            RETURNING r.operation_id, r.tenant_id, r.retention_months, r.as_of, r.runs""")
            .param("worker", worker).param("until", Timestamp.from(now.plus(lease))).param("now", Timestamp.from(now))
            .param("exclude", exclude.joinToString(","))
            .query { rs, _ -> Request(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getInt(3), rs.getTimestamp(4).toInstant(), rs.getInt(5)) }
            .optional().orElse(null)
        if (request != null && operations.find(request.tenantId, request.operationId)?.status == OperationStatus.PENDING) {
            operations.start(request.tenantId, request.operationId)
        }
        Result(request)
    }?.request

    /**
     * 삭제 실행의 결과를 옮긴다. [retentionOperationId] 는 그 실행의 기록이다(기록조차 열지 못했으면 null — 결과는 실패다).
     * 논리 삭제 완료면 작업이 성공으로 닫힌다. 아니면 [giveUp] 일 때만 실패로 닫고, 그 밖에는 요청을 풀어 다음 실행이 이어서 끝낸다.
     * 선점을 잃었으면(기한이 지나 다른 실행이 잡았으면) 아무것도 하지 않고 false — 그 실행이 같은 입력으로 이어서 끝낸다.
     */
    fun finish(request: Request, worker: String, retentionOperationId: UUID?, result: RunResult, giveUp: Boolean, now: Instant): Boolean = requireNotNull(tx.execute {
        if (!held(request, worker)) return@execute false
        retentionOperationId?.let { operations.attachRetention(request.tenantId, request.operationId, it) }
        when {
            result == RunResult.LOGICALLY_DELETED -> {
                operations.succeed(request.tenantId, request.operationId, TARGET)
                close(request.operationId, "logically_deleted", now)
            }
            giveUp -> {
                operations.fail(request.tenantId, request.operationId, TARGET, if (result == RunResult.INCOMPLETE) RETENTION_INCOMPLETE else RETENTION_FAILED)
                close(request.operationId, "failed", now)
            }
            else -> release(request.operationId)
        }
        true
    })

    fun find(operationId: UUID): Map<String, Any?>? = jdbc.sql("SELECT * FROM enrollment.retention_cleanup_requests WHERE operation_id=:id")
        .param("id", operationId).query().listOfRows().firstOrNull()

    private fun held(request: Request, worker: String): Boolean =
        jdbc.sql("SELECT claimed_by FROM enrollment.retention_cleanup_requests WHERE operation_id=:id AND closed_at IS NULL FOR UPDATE")
            .param("id", request.operationId).query(String::class.java).optional().orElse(null) == worker

    private fun release(operationId: UUID) {
        jdbc.sql("UPDATE enrollment.retention_cleanup_requests SET claimed_by=NULL, claimed_until=NULL WHERE operation_id=:id").param("id", operationId).update()
    }

    private fun close(operationId: UUID, outcome: String, now: Instant) {
        jdbc.sql("UPDATE enrollment.retention_cleanup_requests SET closed_at=:now, outcome=:outcome, claimed_by=NULL, claimed_until=NULL WHERE operation_id=:id")
            .param("now", Timestamp.from(now)).param("outcome", outcome).param("id", operationId).update()
    }

    private data class Result(val request: Request?)

    companion object {
        /** 정리 작업의 대상 — 그 조직의 분석 원본(모든 집계의 원천). */
        const val TARGET = "analysis_source"
        const val SUPERSEDED = "superseded"
        const val RETENTION_FAILED = "retention_failed"
        const val RETENTION_INCOMPLETE = "retention_incomplete"
    }
}
