package com.team376.pulsemetry.persistence.enrollment.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * installation 의 장기 신원 자격증명 = `installation_token` 의 해시.
 *
 * 원본 토큰은 사용자 PC 의 OS 키링에만 있고 서버는 해시만 안다 (PLAN.md L7·L11).
 * telemetry token 재발급의 근거가 된다.
 */
@Entity
@Table(name = "installation_credentials", schema = "enrollment")
class InstallationCredential(

	@Column(name = "installation_id", nullable = false)
	var installationId: UUID,

	@Column(name = "credential_hash", nullable = false, length = 255)
	var credentialHash: String,

	@Id
	@Column(name = "id", nullable = false)
	var id: UUID = UUID.randomUUID(),

	@Column(name = "issued_at", nullable = false)
	var issuedAt: Instant = Instant.now(),

	@Column(name = "last_used_at")
	var lastUsedAt: Instant? = null,

	@Column(name = "revoked_at")
	var revokedAt: Instant? = null,
) {
	fun isRevoked(): Boolean = revokedAt != null
}
