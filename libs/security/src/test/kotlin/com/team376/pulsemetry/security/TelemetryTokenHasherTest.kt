package com.team376.pulsemetry.security

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 해시는 계약이다. 이 테스트의 고정 벡터가 어긋나면 발급된 모든 토큰이 401 이 된다
 * (허브 `contracts/enrollment-api.md` §4).
 */
class TelemetryTokenHasherTest {

	@Test
	@DisplayName("auth-proxy 와 같은 값을 낸다 — ai-telemetry-pipeline seed.sql 의 고정 벡터")
	fun matchesAuthProxySeedVector() {
		// ai-telemetry-pipeline/sql/rds/seed.sql 의 Alice 토큰.
		val hasher = TelemetryTokenHasher("local-development-secret-change-me")

		assertThat(hasher.hex("06ab85c6d1396d4cc4de6b3415ebed7aa23f3b02ac696fd290dcc0c358a90668"))
			.isEqualTo("e8238d483da112fc4107648b7644848adf9d48e57bfd99c33f02ead2f8f410e6")
	}

	@Test
	@DisplayName("auth-proxy 자신의 단위 테스트 벡터와도 같다 — 두 구현이 같은 함수라는 증거")
	fun matchesAuthProxyUnitTestVector() {
		// ai-telemetry-pipeline/apps/auth-proxy/tests/shared/token-hash.test.ts 가 고정한 값이다.
		// 위 seed 벡터는 데이터가 출처이고 이쪽은 구현이 출처라, 둘을 같이 두면
		// 어느 한쪽이 갱신돼도 드리프트가 드러난다.
		val hasher = TelemetryTokenHasher("test-token-hash-secret")

		assertThat(hasher.hex("pmt_live_example"))
			.isEqualTo("1173f322abc6c42104fccdd578658dbe7fe0c854f817660b812c5e97f21db94d")
	}

	@Test
	@DisplayName("해시 대상은 ptt_ 접두어를 포함한 발급 문자열 전문이다")
	fun hashesTheFullIssuedString() {
		val hasher = TelemetryTokenHasher("k")
		val token = "ptt_bxoBSKxLYQ0T3rIxbXpZDLtjKvKmVv0pDczAw1ipBmg"

		// 접두어를 뗀 값과 해시가 같다면 어딘가에서 접두어를 잘라 먹고 있는 것이다.
		assertThat(hasher.hex(token)).isNotEqualTo(hasher.hex(token.removePrefix("ptt_")))
	}

	@Test
	@DisplayName("hex 소문자 64자 — 무염 SHA-256 과는 다른 값이다")
	fun hexShapeAndNotPlainSha256() {
		val hasher = TelemetryTokenHasher("some-secret")
		val token = "ptt_example"

		assertThat(hasher.hex(token)).hasSize(64).matches("^[0-9a-f]{64}$")
		// 초대 코드·installation_token 은 무염 SHA-256 이다. 셋을 헷갈리면 조회가 전부 빗나간다.
		assertThat(hasher.hex(token)).isNotEqualTo(plainSha256Hex(token))
	}

	@Test
	@DisplayName("UTF-8 로 인코딩한다 — latin1 이면 비ASCII 토큰에서 갈라진다")
	fun encodesUtf8() {
		// 원본의 "토큰-✓" 케이스. 토큰 자체는 base64url 이라 ASCII 지만,
		// 인코딩이 계약의 일부라는 사실은 양쪽에서 같이 고정해 둔다.
		val hasher = TelemetryTokenHasher("비밀")

		assertThat(hasher.hex("토큰-✓")).matches("^[0-9a-f]{64}$")
	}

	@Test
	@DisplayName("키가 다르면 값이 다르다 — 키 회전은 곧 전 토큰 무효다")
	fun differentKeysDiverge() {
		val token = "ptt_bxoBSKxLYQ0T3rIxbXpZDLtjKvKmVv0pDczAw1ipBmg"

		assertThat(TelemetryTokenHasher("key-a").hex(token))
			.isNotEqualTo(TelemetryTokenHasher("key-b").hex(token))
	}

	@Test
	@DisplayName("키가 비어 있으면 생성 자체가 실패한다 — 애플리케이션이 뜨지 않는다")
	fun blankSecretFailsFast() {
		listOf("", "   ").forEach { blank ->
			assertThatThrownBy { TelemetryTokenHasher(blank) }
				.isInstanceOf(IllegalArgumentException::class.java)
				.hasMessageContaining("pulsemetry.token-hash-secret")
		}
	}

	private fun plainSha256Hex(value: String): String =
		MessageDigest.getInstance("SHA-256")
			.digest(value.toByteArray(StandardCharsets.UTF_8))
			.joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }
}
