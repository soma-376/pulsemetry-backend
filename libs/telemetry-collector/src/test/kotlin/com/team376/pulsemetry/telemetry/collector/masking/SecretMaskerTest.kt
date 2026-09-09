package com.team376.pulsemetry.telemetry.collector.masking

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * 값 마스킹의 특성화 테스트.
 *
 * golden 은 상위 Go 정규식 엔진으로 구웠다 — `src/test/resources/masking-golden.tsv` 머리말이
 * 굽는 방법과 적용 순서의 근거를 담는다. **기대값을 손으로 고치지 마라.** 값이 어긋나면 고칠 곳은
 * 이 파일이 아니라 [SecretMasker] 이거나, 상위 동작이 정말 바뀐 것이다.
 */
class SecretMaskerTest {

	private val masker = SecretMasker()

	@ParameterizedTest(name = "[{0}]")
	@MethodSource("goldenCases")
	@DisplayName("Go 로 구운 golden 과 값이 같다 — 열넷 전부와 겹침·비매치 경계")
	fun matchesGoldenBakedFromGo(input: String, expected: String) {
		assertThat(masker.maskString(input)).isEqualTo(expected)
	}

	@Test
	@DisplayName("겹치는 규칙은 선언 순서로 갈린다 — v0.157.0 의 무작위 순서를 따르지 않는다")
	fun overlappingRulesFollowDeclarationOrder() {
		// #2 sk-[A-Za-z0-9]{20,} 가 #14 key=value 보다 먼저 선언돼 있다. #2 가 먼저 값을 바꾸면
		// 남은 "token=****" 는 #14 의 {6,} 를 채우지 못해 더 마스킹되지 않는다.
		// 순서를 뒤집으면 "****" 가 나온다 — v0.157.0 의 Go 는 실행마다 둘 사이를 오갔다.
		assertThat(masker.maskString("token=sk-abcdefghij1234567890")).isEqualTo("token=****")
	}

	@Test
	@DisplayName("매치 구간만 바꾼다 — 값 전체를 지우지 않는다")
	fun replacesOnlyTheMatchedSpan() {
		assertThat(masker.maskString("Authorization header was sk-abcdefghij1234567890 sent"))
			.isEqualTo("Authorization header was **** sent")
	}

	@Test
	@DisplayName("한 값에 여러 시크릿이 있으면 각각 지운다")
	fun masksEverySecretInOneValue() {
		assertThat(masker.maskString("first sk-abcdefghij1234567890 then AKIAEEEEEEEEEEEEEEEE done"))
			.isEqualTo("first **** then **** done")
	}

	@Test
	@DisplayName("규칙에 걸리지 않는 값은 글자 하나도 바뀌지 않는다")
	fun leavesCleanValuesAlone() {
		listOf("claude_code.user_prompt", "sess-e2e-0001", "alice@acme.test", "4111111111111111", "")
			.forEach { assertThat(masker.maskString(it)).isEqualTo(it) }
	}

	private companion object {

		/**
		 * golden 을 읽는다. 이스케이프 규칙은 굽는 쪽과 같다 — `\\` 를 먼저 풀면 안 되므로
		 * 한 번의 순회로 처리한다.
		 */
		@JvmStatic
		fun goldenCases(): Stream<Arguments> {
			val text = SecretMaskerTest::class.java.getResourceAsStream("/masking-golden.tsv")
				?.bufferedReader()?.readText()
				?: error("masking-golden.tsv 를 찾지 못했다")

			return text.lineSequence()
				.filter { it.isNotBlank() && !it.startsWith("#") }
				.map { line ->
					val (input, expected) = line.split('\t', limit = 2)
					Arguments.of(unescape(input), unescape(expected))
				}
				.toList()
				.stream()
		}

		private fun unescape(value: String): String {
			val sb = StringBuilder(value.length)
			var i = 0
			while (i < value.length) {
				val c = value[i]
				if (c != '\\' || i + 1 >= value.length) {
					sb.append(c)
					i++
					continue
				}
				when (val next = value[i + 1]) {
					'n' -> sb.append('\n')
					't' -> sb.append('\t')
					'r' -> sb.append('\r')
					'v' -> sb.append('\u000B')
					'\\' -> sb.append('\\')
					else -> sb.append(c).append(next)
				}
				i += 2
			}
			return sb.toString()
		}
	}
}
