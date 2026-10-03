package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.persistence.enrollment.management.OrganizationPolicySettings
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant
import java.util.UUID

/**
 * 조직의 유효 정책 설정 (ADR 0046) — enrollment 가 저장한 회수 기준·집계 보존을 원천 계정으로 읽는다(현재 상태, 요청마다).
 * 조직이 회수 기준을 정하지 않았으면 이 서버의 기본 설정(`pulsemetry.dashboard.members.idle-days`), 집계 보존은 무기한(null)이다.
 * 설정·구성원·회수 후보가 이 한 곳에서 값과 판을 읽는다 — 같은 조직의 같은 판이다.
 */
class OrganizationPolicies(
	private val source: JdbcClient,
	private val defaultIdleDays: Int,
) {

	data class Effective(
		val reclaimIdleDays: Int,
		/** `organization`(조직이 저장한 값) 또는 `default`(서버 기본 설정). */
		val reclaimIdleDaysSource: String,
		val aggregateRetentionMonths: Int?,
		/** 조직 정책 설정의 판. 저장한 적 없으면 0 이다. manifest 판과 별개다. */
		val version: Long,
		val updatedAt: Instant?,
		val updatedBy: UUID?,
	)

	fun of(tenant: UUID): Effective {
		val stored = OrganizationPolicySettings.read(source, tenant)
		return Effective(
			reclaimIdleDays = stored.reclaimIdleDays ?: defaultIdleDays,
			reclaimIdleDaysSource = if (stored.reclaimIdleDays == null) DEFAULT else ORGANIZATION,
			aggregateRetentionMonths = stored.aggregateRetentionMonths,
			version = stored.version,
			updatedAt = stored.updatedAt,
			updatedBy = stored.updatedBy,
		)
	}

	companion object {
		const val ORGANIZATION = "organization"
		const val DEFAULT = "default"
	}
}
