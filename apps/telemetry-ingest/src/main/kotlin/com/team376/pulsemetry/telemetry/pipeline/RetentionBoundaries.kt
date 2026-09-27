package com.team376.pulsemetry.telemetry.pipeline

import com.team376.pulsemetry.persistence.telemetry.AnalysisWriteBoundary
import com.team376.pulsemetry.persistence.telemetryops.TenantRetentionBoundaryStore
import java.util.UUID

/** 적재 직전에 tenant 의 삭제 경계를 읽는 자리(ADR 0024 §3). [IngestPipeline] 이 push 마다 한 번 부른다. */
fun interface RetentionBoundaries {
	fun read(tenantId: String): AnalysisWriteBoundary
}

/**
 * RDS `telemetry_ops.tenant_retention_boundary` 에서 읽는다. **캐시하지 않는다** — push 마다 PK 조회 하나다(ADR 0024 §3).
 *
 * 읽지 못하면 허용하지 않는다. 일시 장애는 `TelemetryOpsUnavailableException`(503) 그대로이고, 분류되지 않은 `SQLException`
 * (검사 예외)은 감싸서 올린다 — 수집 진입점은 `RuntimeException` 만 503 으로 돌린다.
 */
class RdsRetentionBoundaries(private val store: TenantRetentionBoundaryStore) : RetentionBoundaries {

	override fun read(tenantId: String): AnalysisWriteBoundary {
		val boundary = try {
			store.read(UUID.fromString(tenantId))
		} catch (exception: RuntimeException) {
			throw exception
		} catch (exception: Exception) {
			throw IllegalStateException("telemetry_ops retention boundary: ${exception.message}", exception)
		}
		return AnalysisWriteBoundary(tenantId, boundary.deletedBefore, boundary.policyEpoch)
	}
}
