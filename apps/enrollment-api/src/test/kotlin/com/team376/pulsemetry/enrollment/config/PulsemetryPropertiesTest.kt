package com.team376.pulsemetry.enrollment.config

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * public-base-url 검증 (PLAN.md §6.8).
 *
 * 이 값은 설치 명령 문자열에 그대로 박힌다 — 잘못된 값은 사용자 터미널이 아니라
 * 기동 시점에 운영자에게 실패로 보여야 한다.
 */
class PulsemetryPropertiesTest {

	@Test
	@DisplayName("정상 http(s) 주소는 통과한다")
	fun acceptsWellFormedUrls() {
		assertThatCode { properties("https://get.pulsemetry.example.com") }.doesNotThrowAnyException()
		assertThatCode { properties("http://localhost:8080") }.doesNotThrowAnyException()
		assertThatCode { properties("https://get.example.com/base/") }.doesNotThrowAnyException()
	}

	@Test
	@DisplayName("host 가 비어 있으면 기동 실패다")
	fun rejectsEmptyHost() {
		assertThatThrownBy { properties("http:///") }
			.isInstanceOf(IllegalArgumentException::class.java)
		assertThatThrownBy { properties("https://") }
			.isInstanceOf(IllegalArgumentException::class.java)
	}

	@Test
	@DisplayName("쿼리·fragment·user-info 가 붙으면 기동 실패다 — 설치 명령에 박혀 터미널에서야 깨진다")
	fun rejectsStructuralNoise() {
		assertThatThrownBy { properties("https://get.example.com?x=1") }
			.isInstanceOf(IllegalArgumentException::class.java)
		assertThatThrownBy { properties("https://get.example.com#frag") }
			.isInstanceOf(IllegalArgumentException::class.java)
		assertThatThrownBy { properties("https://user:pw@get.example.com") }
			.isInstanceOf(IllegalArgumentException::class.java)
	}

	@Test
	@DisplayName("셸 메타문자 화이트리스트 검증은 그대로 살아 있다 — 목적이 다른 별도 방어다")
	fun keepsShellMetacharacterWhitelist() {
		assertThatThrownBy { properties("https://get.example.com/\$(rm -rf ~)") }
			.isInstanceOf(IllegalArgumentException::class.java)
		assertThatThrownBy { properties("ftp://get.example.com") }
			.isInstanceOf(IllegalArgumentException::class.java)
	}

	private fun properties(baseUrl: String) = PulsemetryProperties(
		publicBaseUrl = baseUrl,
		admin = PulsemetryProperties.Admin("test-admin-token"),
		tokenHashSecret = "test-token-hash-secret",
	)
}
