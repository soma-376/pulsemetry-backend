package com.team376.pulsemetry.persistence.enrollment.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * CLI 설치를 허용하는 일회성 초대 코드.
 *
 * 원본 코드는 발급 응답에서 딱 한 번만 나가고 DB 에는 [codeHash] (SHA-256 hex 소문자 64자) 만 남는다.
 * 소비·폐기는 반드시 조건부 UPDATE 한 방으로 한다 — 리포지토리를 보라.
 */
@Entity
@Table(name = "invitations", schema = "enrollment")
class Invitation(

	@Column(name = "tenant_id", nullable = false)
	var tenantId: UUID,

	@Column(name = "target_member_id", nullable = false)
	var targetMemberId: UUID,

	@Column(name = "created_by_member_id", nullable = false)
	var createdByMemberId: UUID,

	@Column(name = "code_hash", nullable = false, length = 255)
	var codeHash: String,

	@Column(name = "expires_at", nullable = false)
	var expiresAt: Instant,

	@Id
	@Column(name = "id", nullable = false)
	var id: UUID = UUID.randomUUID(),

	@Column(name = "used_at")
	var usedAt: Instant? = null,

	@Column(name = "created_at", nullable = false)
	var createdAt: Instant = Instant.now(),

	@Column(name = "revoked_at")
	var revokedAt: Instant? = null,
) {
	fun isUsed(): Boolean = usedAt != null

	fun isRevoked(): Boolean = revokedAt != null

	fun isExpiredAt(now: Instant): Boolean = !expiresAt.isAfter(now)
}
