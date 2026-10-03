package com.team376.pulsemetry.enrollment.service

import com.team376.pulsemetry.enrollment.error.EnrollmentException
import com.team376.pulsemetry.enrollment.secret.Sha256
import com.team376.pulsemetry.persistence.enrollment.entity.Installation
import com.team376.pulsemetry.persistence.enrollment.entity.InstallationCredential
import com.team376.pulsemetry.persistence.enrollment.repository.InstallationCredentialRepository
import com.team376.pulsemetry.persistence.enrollment.repository.InstallationRepository
import org.springframework.stereotype.Component

/**
 * `Authorization: Bearer <installation_token>` 의 검증. 설치 자격증명으로 인증하는 경로가 같은 판정을 쓴다.
 *
 * 토큰이 없거나 형식이 아니거나 폐기됐으면 존재 여부를 알려 주지 않고 401 이다.
 * 자격증명은 있는데 설치가 폐기됐으면 403 `installation_revoked` 다.
 */
@Component
class InstallationCredentialVerifier(
	private val credentials: InstallationCredentialRepository,
	private val installations: InstallationRepository,
) {

	/** 자격증명과 **행을 잠근** 설치. 호출자의 트랜잭션 안에서 부른다 — 잠금이 같은 설치의 요청을 직렬화한다. */
	class Verified(val credential: InstallationCredential, val installation: Installation)

	/** 자격증명만 본다. 잠그지 않는다. 본문을 읽기 전에 인증되지 않은 요청을 걸러 내는 용도다. */
	fun credential(authorizationHeader: String?): InstallationCredential {
		val presentedToken = bearerToken(authorizationHeader) ?: throw EnrollmentException.unauthorized()
		val credential = credentials.findByCredentialHash(Sha256.hex(presentedToken))
			?: throw EnrollmentException.unauthorized()
		if (credential.isRevoked()) throw EnrollmentException.unauthorized()
		return credential
	}

	fun verify(authorizationHeader: String?): Verified {
		val credential = credential(authorizationHeader)
		// 자격증명은 있는데 installation 이 없다면 데이터가 깨진 것이다. 인증 실패로 다룬다.
		val installation = installations.findWithLockById(credential.installationId)
			?: throw EnrollmentException.unauthorized()
		if (!installation.isActive()) throw EnrollmentException.installationRevoked()
		return Verified(credential, installation)
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
