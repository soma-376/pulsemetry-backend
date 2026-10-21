package com.team376.pulsemetry.persistence.enrollment.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * OTLP 헤더에 실려 나가는 교체 가능한 토큰의 자격 기록.
 *
 * installation 과 1:N 이며 재발급 때마다 새 행이 생기고 이전 행은 `revokedAt` 이 채워진다 (PLAN.md L7).
 *
 * telemetry JWT(ADR 0065)이면 이 행의 [id] 가 JWT 의 `jti` 이고 [issuedAt]·[expiresAt]·[signingKid] 가 JWT 의
 * `iat`·`exp`·`kid` 와 같다. [tokenHash] 는 형식과 무관하게 Bearer 전문의 HMAC 이다 — 구 ingest 롤백 호환.
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

	@Enumerated(EnumType.STRING)
	@Column(name = "token_format", nullable = false, length = 16)
	var tokenFormat: TelemetryTokenFormat = TelemetryTokenFormat.opaque,

	@Column(name = "expires_at")
	var expiresAt: Instant? = null,

	@Column(name = "signing_kid", length = 128)
	var signingKid: String? = null,

	@Enumerated(EnumType.STRING)
	@Column(name = "revoke_reason", length = 16)
	var revokeReason: TelemetryTokenRevokeReason? = null,
) {
	fun isRevoked(): Boolean = revokedAt != null
}

/**
 * `telemetry_tokens.token_format`. native enum 이 아니라 `varchar + CHECK` 다(V33 — 서버 전용 값, AGENTS.md).
 * 상수 이름이 곧 DB 값이라 소문자다.
 */
enum class TelemetryTokenFormat { opaque, jwt }

/**
 * `telemetry_tokens.revoke_reason`. `varchar + CHECK` 다(V33). NULL 은 사유 없음이고, 폐기 시각이 있으면 사유 불명 폐기다.
 *
 * - [rotated] — 재발급이 이전 활성 토큰을 폐기했다. 접수 시각이 폐기 시각보다 이르면 Consumer 가 허용한다.
 * - [security] — 운영 절차의 보안 폐기. 접수 시각과 무관하게 거부한다.
 */
enum class TelemetryTokenRevokeReason { rotated, security }
