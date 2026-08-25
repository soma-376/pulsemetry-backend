package com.team376.pulsemetry.enrollment.service

import com.team376.pulsemetry.enrollment.contract.TelemetryTokenResponse
import com.team376.pulsemetry.enrollment.error.EnrollmentException
import com.team376.pulsemetry.enrollment.secret.SecretToken
import com.team376.pulsemetry.enrollment.secret.Sha256
import com.team376.pulsemetry.enrollment.secret.TelemetryTokenHasher
import com.team376.pulsemetry.persistence.enrollment.entity.TelemetryToken
import com.team376.pulsemetry.persistence.enrollment.repository.InstallationCredentialRepository
import com.team376.pulsemetry.persistence.enrollment.repository.InstallationRepository
import com.team376.pulsemetry.persistence.enrollment.repository.TelemetryTokenRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * `POST /v1/installations/telemetry-token` — 장기 자격증명으로 교체 가능한 토큰을 다시 받는다 (PLAN.md §6.3).
 *
 * 2단 토큰 모델의 핵심 동선이다 (L7): OS 키링에만 있는 `installation_token` 을 제시하면
 * OTLP 헤더에 실을 `telemetry_token` 을 새로 내준다. 기존 토큰은 전부 폐기되므로
 * 유출된 telemetry token 은 재발급 한 번으로 무효가 된다.
 */
@Service
class TelemetryTokenService(
	private val credentials: InstallationCredentialRepository,
	private val installations: InstallationRepository,
	private val telemetryTokens: TelemetryTokenRepository,
	private val telemetryTokenHasher: TelemetryTokenHasher,
	private val clock: Clock,
) {

	@Transactional
	fun reissue(authorizationHeader: String?): TelemetryTokenResponse {
		val now = clock.instant()

		// 제시된 토큰이 없거나 형식이 아니면 존재 여부를 알려 주지 않고 그냥 401 이다.
		val presentedToken = bearerToken(authorizationHeader) ?: throw EnrollmentException.unauthorized()

		val credential = credentials.findByCredentialHash(Sha256.hex(presentedToken))
			?: throw EnrollmentException.unauthorized()
		if (credential.isRevoked()) throw EnrollmentException.unauthorized()

		val installation = installations.findById(credential.installationId).orElseThrow {
			// 자격증명은 있는데 installation 이 없다면 데이터가 깨진 것이다. 인증 실패로 다룬다.
			EnrollmentException.unauthorized()
		}
		if (!installation.isActive()) throw EnrollmentException.installationRevoked()

		// 살아있는 토큰을 먼저 전부 폐기한다. 재발급은 곧 이전 토큰의 무효화다.
		telemetryTokens.revokeActiveByInstallationId(installation.id, now)

		val telemetryToken = SecretToken.telemetryToken()
		telemetryTokens.save(
			TelemetryToken(
				installationId = installation.id,
				tokenHash = telemetryTokenHasher.hex(telemetryToken),
				issuedAt = now,
			),
		)
		credentials.touchLastUsedAt(credential.id, now)

		return TelemetryTokenResponse(
			installationId = installation.id.toString(),
			telemetryToken = telemetryToken,
		)
	}

	/**
	 * `Authorization: Bearer <token>` 에서 토큰만 꺼낸다.
	 *
	 * 스킴 비교는 대소문자를 가리지 않는다(RFC 7235). 값이 비면 null 이다.
	 */
	private fun bearerToken(header: String?): String? {
		if (header == null) return null
		if (!header.regionMatches(0, BEARER_PREFIX, 0, BEARER_PREFIX.length, ignoreCase = true)) return null
		return header.substring(BEARER_PREFIX.length).trim().takeIf { it.isNotEmpty() }
	}

	private companion object {
		const val BEARER_PREFIX = "Bearer "
	}
}
