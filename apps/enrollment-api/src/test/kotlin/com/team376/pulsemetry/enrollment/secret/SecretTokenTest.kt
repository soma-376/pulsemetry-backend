package com.team376.pulsemetry.enrollment.secret

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Base64

class SecretTokenTest {

	private val decoder: Base64.Decoder = Base64.getUrlDecoder()

	@Test
	@DisplayName("installation token 은 pit_ 접두사를 갖는다")
	fun installationTokenPrefix() {
		assertThat(SecretToken.installationToken()).startsWith("pit_")
	}

	@Test
	@DisplayName("telemetry token 은 ptt_ 접두사를 갖는다")
	fun telemetryTokenPrefix() {
		assertThat(SecretToken.telemetryToken()).startsWith("ptt_")
	}

	@Test
	@DisplayName("두 토큰의 접두사가 다르다 — 역할이 다른 자격증명이다 (L7)")
	fun prefixesDiffer() {
		assertThat(SecretToken.INSTALLATION_TOKEN_PREFIX)
			.isNotEqualTo(SecretToken.TELEMETRY_TOKEN_PREFIX)
	}

	@Test
	@DisplayName("접두사 뒤는 패딩 없는 base64url 32바이트다")
	fun secretIsBase64UrlOf32Bytes() {
		listOf(SecretToken.installationToken(), SecretToken.telemetryToken()).forEach { token ->
			val secret = token.substring(4)

			assertThat(secret).doesNotContain("=").doesNotContain("+").doesNotContain("/")
			assertThat(decoder.decode(secret)).hasSize(32)
		}
	}

	@Test
	@DisplayName("URL·헤더에 넣어도 안전한 문자만 쓴다")
	fun secretIsUrlSafe() {
		val secret = SecretToken.telemetryToken().substring(4)

		assertThat(secret).matches("^[A-Za-z0-9_-]+$")
	}

	@Test
	@DisplayName("토큰이 반복되지 않는다")
	fun tokensAreUnique() {
		val tokens = (1..2_000).map { SecretToken.installationToken() }.toSet()

		assertThat(tokens).hasSize(2_000)
	}

	@Test
	@DisplayName("두 종류의 토큰이 서로 섞이지 않는다")
	fun tokenKindsDoNotCollide() {
		val installation = (1..500).map { SecretToken.installationToken() }.toSet()
		val telemetry = (1..500).map { SecretToken.telemetryToken() }.toSet()

		assertThat(installation).doesNotContainAnyElementsOf(telemetry)
	}
}
