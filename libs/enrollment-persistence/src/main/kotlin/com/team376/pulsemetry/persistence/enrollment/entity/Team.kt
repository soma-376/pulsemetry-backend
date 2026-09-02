package com.team376.pulsemetry.persistence.enrollment.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * tenant 내부에서 구성원과 AI 사용량을 구분하는 팀 또는 부서.
 *
 * 허브 계약이 약속하는 **최소 집계 단위**다(`../docs/contracts/data-model.md`).
 * manifest 배정 단위는 아니다 — 그쪽은 tenant 로 확정됐다(허브 ADR 0002).
 */
@Entity
@Table(name = "teams", schema = "enrollment")
class Team(

	@Column(name = "tenant_id", nullable = false)
	var tenantId: UUID,

	@Column(name = "name", nullable = false, length = 100)
	var name: String,

	@Id
	@Column(name = "id", nullable = false)
	var id: UUID = UUID.randomUUID(),

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "status", nullable = false, columnDefinition = "team_status")
	var status: TeamStatus = TeamStatus.active,

	@Column(name = "created_at", nullable = false)
	var createdAt: Instant = Instant.now(),

	@Column(name = "updated_at", nullable = false)
	var updatedAt: Instant = Instant.now(),
) {
	fun isActive(): Boolean = status == TeamStatus.active
}
