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

/** 묶음 판정([TelemetryCredentialAuthorizer.authorizeAll])의 요청 하나 — 레코드의 전달된 claim 과 그 레코드의 접수 시각이다. */
data class CredentialRequest(val auth: GatewayAuthContext, val acceptedAt: Instant)

/**
 * 판정 하나의 결과. [Rejected.code] 는 [TelemetryCredentialRejectedException.code] 와 같은 문자열이다.
 * **DB 장애는 결과가 아니다** — 조회 예외는 [TelemetryCredentialAuthorizer.authorizeAll] 호출 전체의 예외로 나간다.
 */
sealed interface CredentialDecision {
	class Authorized(val credential: AuthorizedTelemetryCredential) : CredentialDecision

	data class Rejected(val code: String) : CredentialDecision
}

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
 * **배치는 한 번에 읽고 같은 표로 판정한다(ADR 0084).** Kafka 배치 소비는 호출마다 [authorizeAll] 로 미완료 레코드의 행을 IN 조회 한 번에
 * 읽고, 레코드마다 자기 [acceptedAt] 으로 위 표를 적용한다. 호출을 넘는 캐시는 없다 — 재호출은 다시 읽는다(ADR 0066 Decision 6).
 * HTTP·단건 Kafka 경로는 [authorize] 로 요청마다 한 행을 읽는다.
 *
 * 조립 앱의 클래스다 — 라이브러리가 아니므로 스테레오타입을 달아도 되지만, 배선을 한 곳에 모으려고 `@Bean` 으로 등록한다.
 */
class TelemetryCredentialAuthorizer(private val telemetryTokens: TelemetryTokenRepository) {

	fun authorize(auth: GatewayAuthContext, acceptedAt: Instant): AuthorizedTelemetryCredential =
		when (val decision = judge(telemetryTokens.findCredentialRowById(auth.credentialId), auth, acceptedAt)) {
			is CredentialDecision.Authorized -> decision.credential
			is CredentialDecision.Rejected -> throw TelemetryCredentialRejectedException(decision.code)
		}

	/**
	 * 배치 소비 호출 하나의 묶음 판정(ADR 0084). 고유 `credentialId` 를 [TelemetryTokenRepository.findCredentialRowsByIds] 한 번으로 읽고,
	 * 요청마다 [authorize] 와 같은 판정을 그 요청의 접수 시각으로 적용해 **요청 순서대로** 돌려준다. 같은 자격이 여러 번 와도 조회는 한 번이다.
	 * 빈 입력이면 조회하지 않는다.
	 *
	 * 거부는 [CredentialDecision.Rejected] 이고 다른 요청의 판정에 영향을 주지 않는다. **조회 예외는 그대로 던진다** — 호출 전체가 일시 실패이고
	 * 어느 요청도 거부가 아니다. 결과를 호출 밖에 두지 않는다 — 부르는 쪽이 재호출마다 다시 부른다.
	 */
	fun authorizeAll(requests: List<CredentialRequest>): List<CredentialDecision> {
		if (requests.isEmpty()) return emptyList()
		val ids = requests.mapTo(LinkedHashSet()) { it.auth.credentialId }
		val rows = telemetryTokens.findCredentialRowsByIds(ids).associateBy { it.tokenId }
		return requests.map { judge(rows[it.auth.credentialId], it.auth, it.acceptedAt) }
	}

	/** 클래스 KDoc 의 판정표 그대로다. 행이 없으면([row] 가 null) `credential_unknown` 이다. 조회하지 않는다. */
	private fun judge(row: TelemetryCredentialRow?, auth: GatewayAuthContext, acceptedAt: Instant): CredentialDecision {
		if (row == null) return rejected("credential_unknown")
		if (row.tokenFormat != JWT_FORMAT) return rejected("credential_format")
		if (!matches(row, auth)) return rejected("credential_mismatch")

		val expiresAt = row.expiresAt ?: return rejected("credential_format")
		if (acceptedAt.isBefore(row.issuedAt.minus(TelemetryJwtContract.CLOCK_SKEW)) ||
			acceptedAt.isAfter(expiresAt.plus(TelemetryJwtContract.CLOCK_SKEW))
		) {
			return rejected("credential_expired")
		}

		val revokedAt = row.revokedAt
		if (revokedAt != null) {
			when (row.revokeReason) {
				ROTATED -> if (!acceptedAt.isBefore(revokedAt)) return rejected("credential_rotated")
				SECURITY -> return rejected("credential_security_revoked")
				else -> return rejected("credential_revoked_unknown")
			}
		}

		TelemetryTokenDecision.rejectHolder(row)?.let { return rejected(it.code) }

		return CredentialDecision.Authorized(
			AuthorizedTelemetryCredential(
				credentialId = row.tokenId,
				tenantId = row.tenantId,
				installationId = row.installationId,
				memberId = row.memberId,
			),
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

	private fun rejected(code: String): CredentialDecision = CredentialDecision.Rejected(code)

	private companion object {
		const val JWT_FORMAT = "jwt"
		const val ROTATED = "rotated"
		const val SECURITY = "security"
	}
}
