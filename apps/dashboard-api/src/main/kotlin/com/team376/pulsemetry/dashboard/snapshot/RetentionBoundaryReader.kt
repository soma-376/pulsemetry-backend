package com.team376.pulsemetry.dashboard.snapshot

import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * tenant 의 삭제 경계를 읽는다(`telemetry_ops.tenant_retention_boundary` — ADR 0021). 읽기만 한다 — 경계를 쓰는 것은 보존 정리의 몫이다.
 *
 * 행이 없으면 경계가 없는 것이다(epoch 0). **읽기에 실패하면 예외다** — 과거 행을 허용하는 쪽으로 실패하지 않는다(ADR 0020 §8).
 */
class RetentionBoundaryReader(
	private val source: JdbcClient,
) {

	data class Boundary(val deletedBefore: Instant?, val policyEpoch: Long) {
		companion object {
			val NONE = Boundary(deletedBefore = null, policyEpoch = 0)
		}
	}

	fun read(tenantId: UUID): Boundary =
		source.sql("SELECT deleted_before, policy_epoch FROM telemetry_ops.tenant_retention_boundary WHERE tenant_id = :tenant")
			.param("tenant", tenantId)
			.query { rs, _ -> Boundary(rs.getObject("deleted_before", OffsetDateTime::class.java).toInstant(), rs.getLong("policy_epoch")) }
			.optional()
			.orElse(Boundary.NONE)
}
