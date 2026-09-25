package com.team376.pulsemetry.persistence.telemetryops

import com.team376.pulsemetry.persistence.telemetryops.TelemetryOpsUnavailableException.Companion.classified
import com.team376.pulsemetry.persistence.telemetryops.TenantIngestSummaryStore.Companion.instant
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/** 보존 작업 한 번의 상태(ADR 0024 §4). */
public enum class RetentionOperationStatus(public val wire: String) {
	RUNNING("running"),

	/** 구 경계로 등록된 INSERT 가 대기 상한 안에 끝나지 않았거나 DELETE 뒤에도 행이 남았다. 다시 실행한다. */
	INCOMPLETE("incomplete"),

	/** 논리 삭제 완료. 물리 제거(merge) 완료가 아니다. */
	LOGICALLY_DELETED("logically_deleted"),

	FAILED("failed"),
	;

	internal companion object {
		fun of(wire: String): RetentionOperationStatus = entries.single { it.wire == wire }
	}
}

/** DELETE 직전에 센 삭제 대상 — 테이블마다 관측 고유 수와 물리 revision 행 수. */
public data class RetentionDeletionCounts(
	public val eventObservations: Long,
	public val eventRows: Long,
	public val metricPointObservations: Long,
	public val metricPointRows: Long,
) {
	init {
		require(eventObservations in 0..eventRows && metricPointObservations in 0..metricPointRows) { "관측 수가 행 수보다 많다: $this" }
	}

	public operator fun plus(other: RetentionDeletionCounts): RetentionDeletionCounts = RetentionDeletionCounts(
		eventObservations + other.eventObservations,
		eventRows + other.eventRows,
		metricPointObservations + other.metricPointObservations,
		metricPointRows + other.metricPointRows,
	)

	public companion object {
		public val NONE: RetentionDeletionCounts = RetentionDeletionCounts(0, 0, 0, 0)
	}
}

/** `telemetry_ops.retention_operations` 한 행. */
public data class RetentionOperation(
	public val operationId: UUID,
	public val tenantId: UUID,
	public val retentionMonths: Int,
	public val asOf: Instant,
	public val requestedBefore: Instant,
	public val deletedBefore: Instant?,
	public val policyEpoch: Long?,
	public val status: RetentionOperationStatus,
	public val startedAt: Instant,
	public val finishedAt: Instant?,
	public val counts: RetentionDeletionCounts?,
	public val detail: String?,
)

/**
 * RDS `telemetry_ops.retention_operations` 의 쓰기 주체(ADR 0024 §5). DDL 이 이 모듈 아래 있다. 보존 작업만 조립한다.
 *
 * 상태는 `running` 에서 한 번만 끝 상태로 간다 — 끝 상태의 행을 다시 바꾸지 않는다(갱신 조건이 `status = 'running'`).
 * 빈이 아니다(ADR 0011).
 */
public class RetentionOperationStore(private val dataSource: DataSource) {

	/** 실행을 연다. 경계는 아직 발효 전이다. */
	public fun start(tenantId: UUID, retentionMonths: Int, asOf: Instant, requestedBefore: Instant, startedAt: Instant): UUID {
		val operationId = UUID.randomUUID()
		update(
			"start",
			"INSERT INTO telemetry_ops.retention_operations " +
				"(operation_id, tenant_id, retention_months, as_of, requested_before, status, started_at) " +
				"VALUES (?, ?, ?, ?, ?, 'running', ?)",
			operationId, tenantId, retentionMonths, time(asOf), time(requestedBefore), time(startedAt),
		)
		return operationId
	}

	/** 경계가 발효됐다 — DELETE 가 쓸 경계와 그 epoch. */
	public fun boundaryApplied(operationId: UUID, deletedBefore: Instant, policyEpoch: Long) {
		running(
			"boundary",
			"UPDATE telemetry_ops.retention_operations SET deleted_before = ?, policy_epoch = ? WHERE operation_id = ? AND status = 'running'",
			time(deletedBefore), policyEpoch, operationId,
		)
	}

	/** 논리 삭제 완료. */
	public fun logicallyDeleted(operationId: UUID, counts: RetentionDeletionCounts, finishedAt: Instant) {
		running(
			"logically deleted",
			"UPDATE telemetry_ops.retention_operations SET status = 'logically_deleted', finished_at = ?, " +
				"event_observations = ?, event_rows = ?, metric_point_observations = ?, metric_point_rows = ? " +
				"WHERE operation_id = ? AND status = 'running'",
			time(finishedAt), counts.eventObservations, counts.eventRows, counts.metricPointObservations, counts.metricPointRows, operationId,
		)
	}

	/** 완료를 선언할 근거가 아직 없다 — 다시 실행한다. 그때까지 센 삭제 수가 있으면 함께 남긴다. */
	public fun incomplete(operationId: UUID, detail: String, counts: RetentionDeletionCounts?, finishedAt: Instant) {
		running(
			"incomplete",
			"UPDATE telemetry_ops.retention_operations SET status = 'incomplete', finished_at = ?, detail = ?, " +
				"event_observations = ?, event_rows = ?, metric_point_observations = ?, metric_point_rows = ? " +
				"WHERE operation_id = ? AND status = 'running'",
			time(finishedAt), detail, counts?.eventObservations, counts?.eventRows, counts?.metricPointObservations, counts?.metricPointRows, operationId,
		)
	}

	public fun failed(operationId: UUID, detail: String, finishedAt: Instant) {
		running(
			"failed",
			"UPDATE telemetry_ops.retention_operations SET status = 'failed', finished_at = ?, detail = ? WHERE operation_id = ? AND status = 'running'",
			time(finishedAt), detail, operationId,
		)
	}

	public fun find(operationId: UUID): RetentionOperation? = classified("retention operation select") {
		dataSource.connection.use { connection ->
			connection.prepareStatement("SELECT * FROM telemetry_ops.retention_operations WHERE operation_id = ?").use { statement ->
				statement.setObject(1, operationId)
				statement.executeQuery().use { rows -> if (rows.next()) operation(rows) else null }
			}
		}
	}

	private fun running(action: String, sql: String, vararg values: Any?) {
		val updated = update(action, sql, *values)
		check(updated == 1) { "보존 작업 기록이 running 이 아니다(이미 끝났거나 없다): $action" }
	}

	private fun update(action: String, sql: String, vararg values: Any?): Int = classified("retention operation $action") {
		dataSource.connection.use { connection ->
			connection.prepareStatement(sql).use { statement ->
				values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
				statement.executeUpdate()
			}
		}
	}

	private fun operation(rows: ResultSet): RetentionOperation {
		val eventRows = rows.getObject("event_rows") as Long?
		return RetentionOperation(
			operationId = rows.getObject("operation_id", UUID::class.java),
			tenantId = rows.getObject("tenant_id", UUID::class.java),
			retentionMonths = rows.getInt("retention_months"),
			asOf = rows.instant("as_of")!!,
			requestedBefore = rows.instant("requested_before")!!,
			deletedBefore = rows.instant("deleted_before"),
			policyEpoch = rows.getObject("policy_epoch") as Long?,
			status = RetentionOperationStatus.of(rows.getString("status")),
			startedAt = rows.instant("started_at")!!,
			finishedAt = rows.instant("finished_at"),
			counts = eventRows?.let {
				RetentionDeletionCounts(
					rows.getLong("event_observations"),
					it,
					rows.getLong("metric_point_observations"),
					rows.getLong("metric_point_rows"),
				)
			},
			detail = rows.getString("detail"),
		)
	}

	private fun time(value: Instant): OffsetDateTime = OffsetDateTime.ofInstant(value, ZoneOffset.UTC)
}
