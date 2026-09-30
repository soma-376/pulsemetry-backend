package com.team376.pulsemetry.enrollment.service

import com.team376.pulsemetry.enrollment.contract.TelemetryTokenResponse
import com.team376.pulsemetry.enrollment.error.EnrollmentException
import com.team376.pulsemetry.enrollment.secret.SecretToken
import com.team376.pulsemetry.security.TelemetryTokenHasher
import com.team376.pulsemetry.persistence.enrollment.entity.TelemetryToken
import com.team376.pulsemetry.persistence.enrollment.repository.InstallationCredentialRepository
import com.team376.pulsemetry.persistence.enrollment.repository.MemberRepository
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
	private val verifier: InstallationCredentialVerifier,
	private val credentials: InstallationCredentialRepository,
	private val members: MemberRepository,
	private val telemetryTokens: TelemetryTokenRepository,
	private val telemetryTokenHasher: TelemetryTokenHasher,
	private val clock: Clock,
) {

	@Transactional
	fun reissue(authorizationHeader: String?): TelemetryTokenResponse {
		val now = clock.instant()

		// 행 잠금으로 재발급을 installation 단위로 직렬화한다. 잠금 없이는 동시 재발급이 서로의
		// 미커밋 INSERT 를 못 봐 활성 토큰 2개가 남는다. 진 쪽은 기다렸다 이어서 성공한다.
		val verified = verifier.verify(authorizationHeader)
		val credential = verified.credential
		val installation = verified.installation

		// pit_ 인증은 과거 enroll 완료의 증명이므로, 여기서도 `invited → active` 를 보정한다.
		// 전환 도입 이전에 enroll 된 invited 구성원이 데몬의 401 → 재발급 루프만으로 복구되는 경로다.
		members.activateInvited(installation.memberId, now)

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
}
