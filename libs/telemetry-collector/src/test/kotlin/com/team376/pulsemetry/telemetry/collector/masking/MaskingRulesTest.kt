package com.team376.pulsemetry.telemetry.collector.masking

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.regex.Pattern
import java.util.stream.Stream

/** 규칙 목록 자체가 지켜야 할 성질. 값이 어떻게 바뀌는지는 `SecretMaskerTest` 가 본다. */
class MaskingRulesTest {

	@Test
	@DisplayName("열넷 그대로다 — 원본 설정에서 규칙이 늘거나 줄면 여기서 걸린다")
	fun hasFourteenRules() {
		assertThat(MaskingRules.BLOCKED_VALUES).hasSize(14)
	}

	@ParameterizedTest(name = "#{0}")
	@MethodSource("ruleIndexes")
	@DisplayName("빈 문자열에 매치되는 규칙이 없다 — 있으면 비문자열 속성이 문자열 \"****\" 로 바뀐다")
	fun noRuleMatchesEmptyString(index: Int) {
		// 상위는 redact_all_types: false 일 때 비문자열 속성에 Str() 의 결과인 ""를 태운다.
		// ""에 매치되는 규칙이 하나라도 생기면 그 순간 int·bool 속성이 문자열로 바뀌어
		// ClickHouse 로 흘러가는 타입이 달라진다. 규칙을 추가할 때 이 테스트가 방어선이다.
		val rule: Pattern = MaskingRules.BLOCKED_VALUES[index]
		assertThat(rule.matcher("").find())
			.describedAs("규칙 %s 가 빈 문자열에 매치된다", rule.pattern())
			.isFalse()
	}

	@Test
	@DisplayName("전부 UNICODE_CASE 로 컴파일된다 — Go 의 (?i) 는 유니코드를 접는다")
	fun compiledWithUnicodeCase() {
		MaskingRules.BLOCKED_VALUES.forEach {
			assertThat(it.flags() and Pattern.UNICODE_CASE).isEqualTo(Pattern.UNICODE_CASE)
		}
	}

	@Test
	@DisplayName("정규식에 Java 의 \\s 를 쓰지 않는다 — 수직탭 때문에 Go 와 갈라진다")
	fun avoidsJavaWhitespaceShorthand() {
		// [\s\S] 는 합집합이라 양쪽 모두 "아무 문자"다. 그것만 예외로 둔다.
		MaskingRules.BLOCKED_VALUES
			.map { it.pattern() }
			.forEach { pattern ->
				val withoutAnyChar = pattern.replace("[\\s\\S]", "")
				assertThat(withoutAnyChar)
					.describedAs("규칙 %s 가 \\s 를 쓴다", pattern)
					.doesNotContain("\\s")
			}
	}

	private companion object {
		@JvmStatic
		fun ruleIndexes(): Stream<Int> = MaskingRules.BLOCKED_VALUES.indices.toList().stream()
	}
}
