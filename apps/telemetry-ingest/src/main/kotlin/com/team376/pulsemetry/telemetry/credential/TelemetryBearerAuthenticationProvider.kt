package com.team376.pulsemetry.telemetry.credential

import com.team376.pulsemetry.security.TelemetryTokenAuthenticationException
import com.team376.pulsemetry.security.TelemetryTokenAuthenticationProvider
import com.team376.pulsemetry.security.TelemetryTokenAuthenticationToken
import com.team376.pulsemetry.security.TelemetryTokenPrincipal
import com.team376.pulsemetry.security.TelemetryTokenRejectionReason
import com.team376.pulsemetry.telemetry.auth.TelemetryJwtException
import com.team376.pulsemetry.telemetry.auth.TelemetryJwtVerifier
import com.team376.pulsemetry.telemetry.ingress.GatewayAuthContext
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.security.core.Authentication
import java.time.Clock

/**
 * HTTP ingest 의 Bearer 를 형식으로 가른다(ADR 0065 Decision 7).
 *
 * - **compact JWS 모양(점 둘)** → telemetry JWT 검증기 → RDB 판정(접수 시각 = 지금). **JWT 를 해시로 조회하지 않는다** —
 *   행에 JWT 의 해시가 있어도(구 ingest 롤백 호환) 이 경로는 서명·용도·시간과 자격 기록으로만 판정한다.
 * - 그 밖(`ptt_`·쓰레기) → 기존 해시 조회 [TelemetryTokenAuthenticationProvider]. 동작은 그대로다.
 *
 * 공개키가 하나도 없으면([verifier] 가 null) JWT 모양은 전부 401 이다 — 공개키를 받지 않은 배포가 그대로 뜨고, 발급 전환
 * (`issue-enabled`)보다 공개키 배포가 앞서야 한다는 순서를 로그로 드러낸다.
 *
 * 401 본문은 경로와 무관하게 하나다(진입점의 상수). 사유는 로그에만 남기고 토큰 원문은 남기지 않는다 — 검증한 `jti` 만 남긴다.
 * **DB 장애는 그대로 올린다** — 필터의 장애 처리기가 503 + `Retry-After` 를 쓴다.
 */
class TelemetryBearerAuthenticationProvider(
	private val opaque: TelemetryTokenAuthenticationProvider,
	private val verifier: TelemetryJwtVerifier?,
	private val authorizer: TelemetryCredentialAuthorizer,
	private val clock: Clock,
) : AuthenticationProvider {

	override fun authenticate(authentication: Authentication): Authentication {
		val token = authentication.credentials as? String
			?: throw TelemetryTokenAuthenticationException(TelemetryTokenRejectionReason.MALFORMED_BEARER)
		if (!TelemetryJwtVerifier.isCompactJws(token)) return opaque.authenticate(authentication)

		val jwtVerifier = verifier ?: throw reject(TelemetryTokenRejectionReason.TELEMETRY_JWT_REJECTED, "jwt_verifier_unconfigured", null)
		val acceptedAt = clock.instant()
		val verified = try {
			jwtVerifier.verify(token, acceptedAt)
		} catch (e: TelemetryJwtException) {
			throw reject(TelemetryTokenRejectionReason.TELEMETRY_JWT_REJECTED, e.reason.code, null)
		}
		val credential = try {
			authorizer.authorize(
				GatewayAuthContext(
					credentialId = verified.credentialId,
					tenantId = verified.tenantId,
					installationId = verified.installationId,
					issuer = verified.issuer,
					keyId = verified.keyId,
					issuedAt = verified.issuedAt,
					expiresAt = verified.expiresAt,
				),
				acceptedAt,
			)
		} catch (e: TelemetryCredentialRejectedException) {
			throw reject(TelemetryTokenRejectionReason.TELEMETRY_CREDENTIAL_REJECTED, e.code, verified.credentialId.toString())
		}

		return TelemetryTokenAuthenticationToken.authenticated(
			TelemetryTokenPrincipal(
				tokenId = credential.credentialId,
				tenantId = credential.tenantId,
				installationId = credential.installationId,
				memberId = credential.memberId,
			),
		)
	}

	override fun supports(authentication: Class<*>): Boolean =
		TelemetryTokenAuthenticationToken::class.java.isAssignableFrom(authentication)

	/** 해시 경로와 같은 로그 포맷이다. `detail` 이 JWT·RDB 판정의 세부 코드다. */
	private fun reject(reason: TelemetryTokenRejectionReason, detail: String, tokenId: String?): TelemetryTokenAuthenticationException {
		log.info("telemetry token 거부: reason={} detail={} tokenId={}", reason.code, detail, tokenId ?: "-")
		return TelemetryTokenAuthenticationException(reason)
	}

	private companion object {
		val log = LoggerFactory.getLogger(TelemetryBearerAuthenticationProvider::class.java)
	}
}
