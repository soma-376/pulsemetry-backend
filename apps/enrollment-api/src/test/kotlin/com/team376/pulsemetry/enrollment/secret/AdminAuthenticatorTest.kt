package com.team376.pulsemetry.enrollment.secret

import com.team376.pulsemetry.enrollment.config.PulsemetryProperties
import com.team376.pulsemetry.enrollment.error.EnrollmentException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class AdminAuthenticatorTest {

	@Test
	@DisplayName("키가 비어 있으면 생성 자체가 실패한다 — 애플리케이션이 뜨지 않는다")
	fun blankTokenFailsFast() {
		listOf("", "   ").forEach { blank ->
			assertThatThrownBy { AdminAuthenticator(properties(blank)) }
				.isInstanceOf(IllegalArgumentException::class.java)
				.hasMessageContaining("pulsemetry.admin.api-token")
		}
	}

	@Test
	@DisplayName("일치하는 키는 통과한다")
	fun matchingTokenPasses() {
		val authenticator = AdminAuthenticator(properties("s3cret"))

		assertThatCode { authenticator.authenticate("s3cret") }.doesNotThrowAnyException()
	}

	@Test
	@DisplayName("키가 없거나 다르면 401 unauthorized 로 같은 실패를 낸다")
	fun mismatchIsUnauthorized() {
		val authenticator = AdminAuthenticator(properties("s3cret"))

		listOf(null, "", "s3crer", "s3cret ", " s3cret", "S3CRET").forEach { presented ->
			assertThatThrownBy { authenticator.authenticate(presented) }
				.describedAs("presented=%s", presented)
				.isInstanceOf(EnrollmentException::class.java)
				.extracting { (it as EnrollmentException).errorCode.code }
				.isEqualTo("unauthorized")
		}
	}

	@Test
	@DisplayName("실패 메시지에 키가 들어가지 않는다 (R4)")
	fun failureLeaksNoToken() {
		val authenticator = AdminAuthenticator(properties("s3cret"))

		val thrown = runCatching { authenticator.authenticate("wrong") }.exceptionOrNull()

		assertThat(thrown!!.message).doesNotContain("s3cret").doesNotContain("wrong")
	}

	private fun properties(adminToken: String) = PulsemetryProperties(
		publicBaseUrl = "https://get.pulsemetry.example.com",
		admin = PulsemetryProperties.Admin(adminToken),
	)
}
