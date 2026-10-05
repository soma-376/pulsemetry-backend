package com.team376.pulsemetry.persistence.enrollment.management

import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant
import java.util.UUID

/**
 * 조직 정책 설정 — 좌석 회수 기준과 집계 보존 (ADR 0046). 설치에 배포하는 정책이 아니라 manifest 와 따로 저장하고 판도 따로 센다.
 * [reclaimIdleDays] 가 null 이면 조직이 정하지 않았다(조회 서버의 기본 설정을 쓴다), [aggregateRetentionMonths] 가 null 이면 무기한이다.
 * 저장한 적 없는 조직은 [UNSET](판 0)이다. 쓰기는 수집 정책 저장 명령(`OnboardingStore.savePolicy`)만 한다.
 */
data class OrganizationPolicySettings(
    val reclaimIdleDays: Int?,
    val aggregateRetentionMonths: Int?,
    val version: Long,
    val updatedAt: Instant?,
    val updatedBy: UUID?,
) {
    companion object {
        /** 화면이 고를 수 있는 회수 기준 일수. */
        val RECLAIM_IDLE_DAYS = listOf(7, 14, 30, 60)
        /** 화면이 고를 수 있는 집계 보존 개월 수. 무기한은 null 이다. */
        val AGGREGATE_RETENTION_MONTHS = listOf(12, 24, 36)
        val UNSET = OrganizationPolicySettings(null, null, 0, null, null)

        /** 조직의 저장값. 없으면 [UNSET]. [forUpdate] 는 저장 명령이 같은 트랜잭션에서 행을 잠글 때만 쓴다. */
        fun read(jdbc: JdbcClient, tenant: UUID, forUpdate: Boolean = false): OrganizationPolicySettings =
            jdbc.sql("SELECT reclaim_idle_days,aggregate_retention_months,version,updated_at,updated_by FROM enrollment.organization_policy_settings " +
                "WHERE tenant_id=:tenant" + if (forUpdate) " FOR UPDATE" else "")
                .param("tenant", tenant)
                .query { rs, _ ->
                    OrganizationPolicySettings(
                        reclaimIdleDays = rs.getObject("reclaim_idle_days")?.let { (it as Number).toInt() },
                        aggregateRetentionMonths = rs.getObject("aggregate_retention_months")?.let { (it as Number).toInt() },
                        version = rs.getLong("version"),
                        updatedAt = rs.getTimestamp("updated_at").toInstant(),
                        updatedBy = rs.getObject("updated_by", UUID::class.java),
                    )
                }
                .optional()
                .orElse(UNSET)
    }
}
