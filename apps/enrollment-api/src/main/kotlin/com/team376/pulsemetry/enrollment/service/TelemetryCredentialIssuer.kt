package com.team376.pulsemetry.enrollment.service

import com.team376.pulsemetry.enrollment.secret.SecretToken
import com.team376.pulsemetry.persistence.enrollment.entity.TelemetryToken
import com.team376.pulsemetry.persistence.enrollment.entity.TelemetryTokenFormat
import com.team376.pulsemetry.security.TelemetryTokenHasher
import com.team376.pulsemetry.telemetry.auth.TelemetryJwtIssuer
import java.time.Instant
import java.util.UUID

/**
 * 발급한 telemetry token 원문과 저장할 자격 기록. 원문은 응답으로 한 번 나가고 끝이다 — DB 에는 해시와 메타데이터만 남는다.
 */
class IssuedTelemetryCredential(
	val token: String,
	val record: TelemetryToken,
) {
	/** 토큰 원문을 문자열 표현에 싣지 않는다. */
	override fun toString(): String = "IssuedTelemetryCredential(id=${record.id}, format=${record.tokenFormat})"
}

/**
 * 최초 enrollment 와 재발급이 함께 쓰는 telemetry token 발급기(ADR 0065 Decision 1·3).
 *
 * [jwt] 가 null 이면 지금처럼 불투명 `ptt_` 다(`pulsemetry.telemetry-jwt.issue-enabled=false`, 기본). 있으면 telemetry JWT 다.
 *
 * ## JWT 의 발급 순서
 *
 * 1. 행 id 를 먼저 만든다 — 그 값이 `jti` 다.
 * 2. 발급기가 `iat` 를 초로 자르고 `exp = iat + 3h` 로 서명한다. 행의 `issued_at`·`expires_at`·`signing_kid` 는 그 값이다.
 * 3. **서명한 문자열 전문의 HMAC 을 `token_hash` 에 저장한다** — 구 ingest 이미지로 되돌려도 해시 조회로 통과한다.
 *
 * 저장·트랜잭션·설치 잠금·이전 토큰 폐기는 호출자(`EnrollmentService`·`TelemetryTokenService`)가 그대로 소유한다.
 * 이 클래스는 행을 만들어 돌려줄 뿐 저장하지 않는다.
 */
class TelemetryCredentialIssuer(
	private val hasher: TelemetryTokenHasher,
	private val jwt: TelemetryJwtIssuer?,
) {

	fun issue(tenantId: UUID, installationId: UUID, now: Instant): IssuedTelemetryCredential {
		if (jwt == null) {
			val token = SecretToken.telemetryToken()
			return IssuedTelemetryCredential(
				token = token,
				record = TelemetryToken(installationId = installationId, tokenHash = hasher.hex(token), issuedAt = now),
			)
		}

		val credentialId = UUID.randomUUID()
		val issued = jwt.issue(credentialId, tenantId, installationId, now)
		return IssuedTelemetryCredential(
			token = issued.token,
			record = TelemetryToken(
				installationId = installationId,
				tokenHash = hasher.hex(issued.token),
				id = credentialId,
				issuedAt = issued.issuedAt,
				tokenFormat = TelemetryTokenFormat.jwt,
				expiresAt = issued.expiresAt,
				signingKid = issued.keyId,
			),
		)
	}
}
