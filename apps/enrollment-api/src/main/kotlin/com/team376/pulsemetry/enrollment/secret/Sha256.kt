package com.team376.pulsemetry.enrollment.secret

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 비밀값을 DB 에 넣기 전에 통과시키는 단방향 해시.
 *
 * bcrypt·Argon2 를 쓰지 않는 이유는 성능이 아니라 **조회** 때문이다 (PLAN.md L11).
 * 초대 코드와 토큰은 `WHERE code_hash = ?` 같은 유니크 인덱스 조회로 찾아야 하므로
 * 같은 입력이 항상 같은 출력을 내는 결정론적 해시여야 한다. salt 를 붙이면 조회가 불가능해진다.
 *
 * 대신 원본이 128비트 이상의 고엔트로피 난수라서 사전 공격 대상이 아니다.
 * 사람이 고른 비밀번호에는 절대 쓰지 마라.
 */
object Sha256 {

	private const val ALGORITHM = "SHA-256"

	/** SHA-256 hex 소문자 64자. `MessageDigest` 는 스레드 안전하지 않아 호출마다 새로 만든다. */
	fun hex(value: String): String =
		MessageDigest.getInstance(ALGORITHM)
			.digest(value.toByteArray(StandardCharsets.UTF_8))
			.joinToString(separator = "") { byte -> HEX_DIGITS[byte.toInt() and 0xFF] }

	private val HEX_DIGITS: Array<String> = Array(256) { "%02x".format(it) }
}
