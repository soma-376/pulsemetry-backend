package com.team376.pulsemetry.enrollment.secret

import java.security.SecureRandom
import java.util.Base64

/**
 * 2단 토큰 모델의 원본 토큰 생성기 (PLAN.md L7).
 *
 * - `pit_` 장기 `installation_token` — 사용자 PC 의 OS 키링에만 남는다. telemetry token 재발급의 근거.
 * - `ptt_` 교체 가능한 `telemetry_token` — OTLP Authorization 헤더에 실린다. 폐기·재발급이 잦다.
 *
 * 둘을 하나로 합치지 마라. 역할이 다르고, 노출 범위도 다르다.
 * 여기서 만든 **원본은 응답으로 한 번 나가고 끝**이다. DB 에는 [Sha256] 해시만 넣는다 (R4·L11).
 */
object SecretToken {

	const val INSTALLATION_TOKEN_PREFIX = "pit_"
	const val TELEMETRY_TOKEN_PREFIX = "ptt_"

	/** 256비트. 봉투 스키마가 명시한 규격이다. */
	private const val ENTROPY_BYTES = 32

	private val random = SecureRandom()
	private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

	fun installationToken(): String = INSTALLATION_TOKEN_PREFIX + randomSecret()

	fun telemetryToken(): String = TELEMETRY_TOKEN_PREFIX + randomSecret()

	/** base64url(32 랜덤 바이트), 패딩 없음. URL·헤더 어디에 넣어도 안전한 문자만 나온다. */
	private fun randomSecret(): String {
		val bytes = ByteArray(ENTROPY_BYTES)
		random.nextBytes(bytes)
		return encoder.encodeToString(bytes)
	}
}
