package com.team376.pulsemetry.enrollment.secret

import com.team376.pulsemetry.enrollment.config.PulsemetryProperties
import com.team376.pulsemetry.enrollment.error.EnrollmentException
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 관리자 API 의 정적 키 검증 (PLAN.md L8 — `X-Admin-Token`).
 *
 * Cognito·OAuth2·JWT 는 지금 붙이지 않는다. 이 서버의 관리자 API 는 대시보드가 생길 때까지
 * 운영자가 직접 부르는 통로이고, 그때 인증 방식을 다시 정한다.
 *
 * 키가 설정되지 않으면 **애플리케이션이 뜨지 않는다.** 빈 문자열을 "인증 없음" 으로 해석하면
 * 설정 실수 하나로 초대 발급이 인터넷에 열린다.
 */
@Component
class AdminAuthenticator(properties: PulsemetryProperties) {

	private val expected: ByteArray

	init {
		val token = properties.admin.apiToken
		require(token.isNotBlank()) {
			"pulsemetry.admin.api-token 이 비어 있다. 관리자 API 키 없이 기동할 수 없다."
		}
		expected = token.toByteArray(StandardCharsets.UTF_8)
	}

	/**
	 * 헤더가 없거나 값이 다르면 401 이다. 둘을 구분해 주지 않는다 —
	 * "헤더는 맞는데 값이 틀렸다" 는 응답은 공격자에게 힌트가 된다.
	 *
	 * 비교는 [MessageDigest.isEqual] 로 한다. `==` 는 첫 불일치에서 즉시 반환하므로
	 * 응답 시간 차이로 키를 한 글자씩 알아낼 수 있다.
	 */
	fun authenticate(presentedToken: String?) {
		if (presentedToken == null) throw EnrollmentException.unauthorized()
		val presented = presentedToken.toByteArray(StandardCharsets.UTF_8)
		if (!MessageDigest.isEqual(expected, presented)) throw EnrollmentException.unauthorized()
	}
}
