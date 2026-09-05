package com.team376.pulsemetry.security

import com.team376.pulsemetry.persistence.enrollment.repository.TelemetryTokenRepository
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.security.core.Authentication

/**
 * `ptt_` 토큰을 해시해 조회하고 거부 사유를 판정한다.
 *
 * **DB 오류를 삼키지 않는다.** 조회가 실패하면 예외가 그대로 올라가 500 이 된다.
 * 401 로 접으면 장애가 "토큰이 틀렸다"로 보여서, 클라이언트가 토큰을 폐기하고 재발급하는
 * 복구 루프(허브 `contracts/telemetry-ingest.md`)를 헛돌게 만든다.
 *
 * **스테레오타입을 달지 않는다** (ADR 0011). 빈 등록과 필터 체인 배선은 앱이 한다.
 */
class TelemetryTokenAuthenticationProvider(
	private val hasher: TelemetryTokenHasher,
	private val telemetryTokens: TelemetryTokenRepository,
) : AuthenticationProvider {

	override fun authenticate(authentication: Authentication): Authentication {
		val token = authentication.credentials as? String
			?: throw TelemetryTokenAuthenticationException(TelemetryTokenRejectionReason.MALFORMED_BEARER)

		val row = telemetryTokens.findAuthRowByTokenHash(hasher.hex(token))
			?: throw reject(TelemetryTokenRejectionReason.TOKEN_UNKNOWN)

		TelemetryTokenDecision.reject(row)?.let { throw reject(it, row.tokenId.toString()) }

		return TelemetryTokenAuthenticationToken.authenticated(
			TelemetryTokenPrincipal(
				tokenId = row.tokenId,
				tenantId = row.tenantId,
				installationId = row.installationId,
				memberId = row.memberId,
			),
		)
	}

	override fun supports(authentication: Class<*>): Boolean =
		TelemetryTokenAuthenticationToken::class.java.isAssignableFrom(authentication)

	/**
	 * 사유를 서버 로그에만 남긴다. **토큰 원문은 담지 않는다** (허브 계약 §4).
	 * 토큰을 특정할 수 있을 때만 그 id 를 남긴다 — 해시조차 원문의 결정론적 함수라 적지 않는다.
	 */
	private fun reject(
		reason: TelemetryTokenRejectionReason,
		tokenId: String? = null,
	): TelemetryTokenAuthenticationException {
		log.info("telemetry token 거부: reason={} tokenId={}", reason.code, tokenId ?: "-")
		return TelemetryTokenAuthenticationException(reason)
	}

	private companion object {
		val log = LoggerFactory.getLogger(TelemetryTokenAuthenticationProvider::class.java)
	}
}
