package com.team376.pulsemetry.enrollment.secret

import com.team376.pulsemetry.enrollment.config.PulsemetryProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class TelemetryTokenHasherTest {

	@Test
	@DisplayName("auth-proxy 와 같은 값을 낸다 — ai-telemetry-pipeline seed.sql 의 고정 벡터")
	fun matchesAuthProxyVector() {
		// ai-telemetry-pipeline/sql/rds/seed.sql 의 Alice 토큰.
		// auth-proxy 는 createHmac("sha256", TOKEN_HASH_SECRET).update(token, "utf8").digest("hex")
		// (apps/auth-proxy/src/shared/crypto/token-hash.ts) 로 조회하므로, 이 벡터가 어긋나면
		// 발급한 모든 토큰이 401 이 된다.
		val hasher = hasher("local-development-secret-change-me")

		assertThat(hasher.hex("06ab85c6d1396d4cc4de6b3415ebed7aa23f3b02ac696fd290dcc0c358a90668"))
			.isEqualTo("e8238d483da112fc4107648b7644848adf9d48e57bfd99c33f02ead2f8f410e6")
	}

	@Test
	@DisplayName("해시 대상은 ptt_ 접두어를 포함한 발급 문자열 전문이다")
	fun hashesTheFullIssuedString() {
		val hasher = hasher("k")
		val token = SecretToken.telemetryToken()

		// 접두어를 뗀 값과 해시가 같다면 어딘가에서 접두어를 잘라 먹고 있는 것이다.
		assertThat(hasher.hex(token)).isNotEqualTo(hasher.hex(token.removePrefix("ptt_")))
	}

	@Test
	@DisplayName("hex 소문자 64자 — 무염 SHA-256 과는 다른 값이다")
	fun hexShapeAndNotPlainSha256() {
		val hasher = hasher("some-secret")
		val token = "ptt_example"

		assertThat(hasher.hex(token)).hasSize(64).matches("^[0-9a-f]{64}$")
		assertThat(hasher.hex(token)).isNotEqualTo(Sha256.hex(token))
	}

	@Test
	@DisplayName("키가 다르면 값이 다르다 — 키 회전은 곧 전 토큰 무효다")
	fun differentKeysDiverge() {
		val token = SecretToken.telemetryToken()

		assertThat(hasher("key-a").hex(token)).isNotEqualTo(hasher("key-b").hex(token))
	}

	@Test
	@DisplayName("키가 비어 있으면 생성 자체가 실패한다 — 애플리케이션이 뜨지 않는다")
	fun blankSecretFailsFast() {
		listOf("", "   ").forEach { blank ->
			assertThatThrownBy { hasher(blank) }
				.isInstanceOf(IllegalArgumentException::class.java)
				.hasMessageContaining("pulsemetry.token-hash-secret")
		}
	}

	private fun hasher(secret: String) = TelemetryTokenHasher(
		PulsemetryProperties(
			publicBaseUrl = "https://get.pulsemetry.example.com",
			admin = PulsemetryProperties.Admin("test-admin-token"),
			tokenHashSecret = secret,
		),
	)
}
