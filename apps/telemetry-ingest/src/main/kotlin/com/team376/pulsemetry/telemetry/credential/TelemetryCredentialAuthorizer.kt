package com.team376.pulsemetry.telemetry.credential

import com.team376.pulsemetry.persistence.enrollment.repository.TelemetryCredentialRow
import com.team376.pulsemetry.persistence.enrollment.repository.TelemetryTokenRepository
import com.team376.pulsemetry.security.TelemetryTokenDecision
import com.team376.pulsemetry.telemetry.auth.TelemetryJwtContract
import com.team376.pulsemetry.telemetry.collector.AuthorizedTelemetryIdentity
import com.team376.pulsemetry.telemetry.ingress.GatewayAuthContext
import java.time.Instant
import java.util.UUID

/** RDB 판정을 통과한 자격. 신원은 전달된 claim 이 아니라 **행**의 tenant·installation 이다. */
class AuthorizedTelemetryCredential(
	val credentialId: UUID,
	val tenantId: UUID,
	val installationId: UUID,
	val memberId: UUID,
) {
	fun identity(): AuthorizedTelemetryIdentity = AuthorizedTelemetryIdentity(tenantId = tenantId, installationId = installationId)
}

/**
 * RDB 판정의 거부. **영구 실패다** — Kafka 는 offset 을 확정하고 HTTP 는 401 이다. [code] 는 로그용이다(`credential_*` 또는
 * `TelemetryTokenDecision` 의 차단 코드). 메시지에 claim 값을 싣지 않는다.
 */
class TelemetryCredentialRejectedException(val code: String) :
	RuntimeException("telemetry credential rejected: $code", null, false, false)

/**
 * telemetry JWT 의 RDB 판정(ADR 0065 Decision 6). Kafka Consumer 는 매 시도, HTTP 호환 경로는 요청마다 부른다.
 *
 * `jti` 로 자격 기록과 설치·회원·조직 상태를 한 번에 읽고 다음 순서로 판정한다. **시간은 Consumer 의 현재 시각이 아니라
 * 접수 시각([acceptedAt])으로 본다** — Kafka 에서 기다리는 동안 만료된 토큰도 접수 당시 유효했으면 처리한다.
 *
 * | 순서 | 판정 | 거부 코드 |
 * |---|---|---|
 * | 1 | 행이 있다 | `credential_unknown` |
 * | 2 | 형식이 `jwt` 다 | `credential_format` |
 * | 3 | 전달된 tenant·installation·iss·kid·iat·exp 가 행과 같다 | `credential_mismatch` |
 * | 4 | `acceptedAt ∈ [issued_at − 30s, expires_at + 30s]` (DB 의 값) | `credential_expired` |
 * | 5 | 폐기됐다면 `rotated` 이고 `acceptedAt < revoked_at` | `credential_rotated`·`credential_security_revoked`·`credential_revoked_unknown` |
 * | 6 | 설치·회원·조직이 차단되지 않았다 — `TelemetryTokenDecision.rejectHolder` 의 규칙과 순서 | 그 코드 |
 *
 * **DB 장애는 거부가 아니다** — 조회 예외를 그대로 올린다. Kafka 는 일시 실패로 재시도하고 HTTP 는 503 이다.
 *
 * 조립 앱의 클래스다 — 라이브러리가 아니므로 스테레오타입을 달아도 되지만, 배선을 한 곳에 모으려고 `@Bean` 으로 등록한다.
 */
class TelemetryCredentialAuthorizer(private val telemetryTokens: TelemetryTokenRepository) {

	fun authorize(auth: GatewayAuthContext, acceptedAt: Instant): AuthorizedTelemetryCredential {
		val row = telemetryTokens.findCredentialRowById(auth.credentialId) ?: reject("credential_unknown")
		if (row.tokenFormat != JWT_FORMAT) reject("credential_format")
		if (!matches(row, auth)) reject("credential_mismatch")

		val expiresAt = row.expiresAt ?: reject("credential_format")
		if (acceptedAt.isBefore(row.issuedAt.minus(TelemetryJwtContract.CLOCK_SKEW)) ||
			acceptedAt.isAfter(expiresAt.plus(TelemetryJwtContract.CLOCK_SKEW))
		) {
			reject("credential_expired")
		}

		val revokedAt = row.revokedAt
		if (revokedAt != null) {
			when (row.revokeReason) {
				ROTATED -> if (!acceptedAt.isBefore(revokedAt)) reject("credential_rotated")
				SECURITY -> reject("credential_security_revoked")
				else -> reject("credential_revoked_unknown")
			}
		}

		TelemetryTokenDecision.rejectHolder(row)?.let { reject(it.code) }

		return AuthorizedTelemetryCredential(
			credentialId = row.tokenId,
			tenantId = row.tenantId,
			installationId = row.installationId,
			memberId = row.memberId,
		)
	}

	/** iss 는 DB 에 없다 — 발급자가 쓰는 고정값과 대조한다. 시각은 DB 의 초 단위 값과 같아야 한다. */
	private fun matches(row: TelemetryCredentialRow, auth: GatewayAuthContext): Boolean =
		row.tenantId == auth.tenantId &&
			row.installationId == auth.installationId &&
			auth.issuer == TelemetryJwtContract.ISSUER &&
			row.signingKid == auth.keyId &&
			row.issuedAt == auth.issuedAt &&
			row.expiresAt == auth.expiresAt

	private fun reject(code: String): Nothing = throw TelemetryCredentialRejectedException(code)

	private companion object {
		const val JWT_FORMAT = "jwt"
		const val ROTATED = "rotated"
		const val SECURITY = "security"
	}
}
