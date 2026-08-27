package com.team376.pulsemetry.enrollment.secret

import java.security.SecureRandom
import java.util.Locale

/**
 * 일회성 초대 코드의 생성·정규화·검증.
 *
 * 형식은 `XXXX-XXXX-XXXX` 이고 글자는 Crockford Base32 다 — `I` `L` `O` `U` 를 뺀 32자.
 * 사용자가 코드를 손으로 옮겨 적거나 불러 주는 상황을 전제하므로 `1`/`I`/`l`, `0`/`O` 처럼
 * 헷갈리는 글자를 애초에 만들지 않는다.
 *
 * 이 정규식은 **주입 방어선**이기도 하다 (PLAN.md A8).
 * 허용 문자에 셸·PowerShell 메타문자가 하나도 없으므로, 부트스트랩 스크립트에 코드를 끼워 넣기 전에
 * 여기를 통과시키는 것만으로 충분하다. 문자열 이스케이프에 기대지 마라 — 이스케이프는 뚫린다.
 */
object InvitationCode {

	/** Crockford Base32: 0-9 + A-Z 에서 I·L·O·U 제외 = 32자. */
	private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

	private const val GROUP_SIZE = 4
	private const val GROUP_COUNT = 3

	/** PLAN.md §6.8 이 못박은 정규식. 느슨하게 바꾸지 마라. */
	const val PATTERN: String =
		"^[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$"

	private val REGEX = Regex(PATTERN)

	private val random = SecureRandom()

	/** 새 초대 코드를 만든다. 알파벳 크기가 32(2의 거듭제곱)라 모듈로 편향이 없다. */
	fun generate(): String = (1..GROUP_COUNT).joinToString(separator = "-") {
		buildString(GROUP_SIZE) {
			repeat(GROUP_SIZE) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
		}
	}

	/**
	 * 사용자가 입력한 코드를 정규 형식으로 맞춘다.
	 *
	 * 앞뒤 공백 제거 → 대문자 → 하이픈이 하나도 없으면 4자마다 삽입.
	 * 그러고도 [PATTERN] 을 만족하지 못하면 **null** 이다 (호출자는 400 `invalid_request` 로 응답한다).
	 *
	 * 화이트리스트 방식이므로 `'; rm -rf /` 같은 입력은 여기서 조용히 걸러진다.
	 */
	fun normalize(raw: String?): String? {
		if (raw == null) return null
		val trimmed = raw.trim().uppercase(Locale.ROOT)
		val hyphenated = if (trimmed.contains('-')) trimmed else trimmed.chunked(GROUP_SIZE).joinToString("-")
		return hyphenated.takeIf { REGEX.matches(it) }
	}

	/** 이미 정규 형식인지만 본다. DB 조회 없이 형식만 검사할 때 쓴다 (코드 탐색 오라클 방지). */
	fun matches(value: String?): Boolean = value != null && REGEX.matches(value)
}
