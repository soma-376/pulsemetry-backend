package com.team376.pulsemetry.persistence.enrollment.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * OTLP 헤더에 실려 나가는 교체 가능한 토큰의 해시.
 *
 * installation 과 1:N 이며 재발급 때마다 새 행이 생기고 이전 행은 `revokedAt` 이 채워진다 (PLAN.md L7).
 */
@Entity
@Table(name = "telemetry_tokens", schema = "enrollment")
class TelemetryToken(

	@Column(name = "installation_id", nullable = false)
	var installationId: UUID,

	@Column(name = "token_hash", nullable = false, length = 255)
	var tokenHash: String,

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
