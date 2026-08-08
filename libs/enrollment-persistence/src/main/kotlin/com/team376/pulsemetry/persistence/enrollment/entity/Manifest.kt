package com.team376.pulsemetry.persistence.enrollment.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * tenant 별 수집·privacy 정책의 버전 이력.
 *
 * 기존 행은 고치지 않고 설정이 바뀌면 새 [version] 행을 만든다.
 * `is_active` 인 행은 tenant 당 최대 하나다 — 부분 유니크 인덱스가 강제한다(SCHEMA-DRIFT).
 *
 * [manifest] 는 jsonb 원문을 그대로 담는다. 봉투(installation_id·토큰)와 섞지 않는다 (PLAN.md A5).
 * 파싱과 `config_revision` 덮어쓰기는 상위 계층의 몫이다.
 */
@Entity
@Table(name = "manifests", schema = "enrollment")
class Manifest(

	@Column(name = "tenant_id", nullable = false)
	var tenantId: UUID,

	@Column(name = "version", nullable = false)
	var version: Int,

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "manifest", nullable = false, columnDefinition = "jsonb")
	var manifest: String,

	@Column(name = "created_by_member_id", nullable = false)
	var createdByMemberId: UUID,

	@Id
	@Column(name = "id", nullable = false)
	var id: UUID = UUID.randomUUID(),

	@Column(name = "is_active", nullable = false)
	var isActive: Boolean = false,

	@Column(name = "created_at", nullable = false)
	var createdAt: Instant = Instant.now(),

	@Column(name = "activated_at")
	var activatedAt: Instant? = null,
)
