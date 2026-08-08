package com.team376.pulsemetry.enrollment.secret

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class Sha256Test {

	@Test
	@DisplayName("알려진 테스트 벡터와 일치한다")
	fun matchesKnownVectors() {
		assertThat(Sha256.hex("abc"))
			.isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
		assertThat(Sha256.hex(""))
			.isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
	}

	@Test
	@DisplayName("항상 소문자 hex 64자다 — code_hash 컬럼 규격")
	fun alwaysLowercaseHex64() {
		listOf("ABCD-EFGH-JKMN", "pit_abc", "", "한글", "0").forEach { input ->
			assertThat(Sha256.hex(input)).hasSize(64).matches("^[0-9a-f]{64}$")
		}
	}

	@Test
	@DisplayName("결정론적이다 — 유니크 인덱스 조회가 성립하려면 필수다 (L11)")
	fun isDeterministic() {
		val code = "ABCD-EFGH-JKMN"

		assertThat(Sha256.hex(code)).isEqualTo(Sha256.hex(code))
	}

	@Test
	@DisplayName("다른 입력은 다른 해시를 낸다")
	fun differentInputsDiffer() {
		assertThat(Sha256.hex("ABCD-EFGH-JKMN")).isNotEqualTo(Sha256.hex("ABCD-EFGH-JKMP"))
	}

	@Test
	@DisplayName("UTF-8 로 인코딩한다 — 플랫폼 기본 인코딩에 기대지 않는다")
	fun usesUtf8() {
		// "한" = EC 95 9C (UTF-8) 의 SHA-256
		assertThat(Sha256.hex("한"))
			.isEqualTo("89233692031a62c60e6ef602b4cd9f3cb52a6dd8013fe9873c36101a7e3920e0")
	}

	@Test
	@DisplayName("원본이 해시에 남지 않는다")
	fun hashLeaksNothing() {
		val secret = "pit_super-secret-value"

		assertThat(Sha256.hex(secret)).doesNotContain("pit_").doesNotContain("secret")
	}
}
