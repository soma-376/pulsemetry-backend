package com.team376.pulsemetry.telemetry.adapter.observation.fixture

import com.team376.pulsemetry.telemetry.adapter.observation.claudecode.ClaudeCodeProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.ProfileRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Claude Code 프로파일의 수용 사례. `claude_code/synthetic/` 은 ADR 0020 부록 A 의 규칙을 드러내는 명세 사례이고,
 * `claude_code/real/` 은 실캡처 익명화 추출본에 명세에서 쓴 기대값을 붙인 것이다(기대값이 있는 파일만).
 */
class ClaudeCodeFixtureTest {

	private val suite = FixtureSuite(ProfileRegistry(listOf(ClaudeCodeProfile)))

	@TestFactory
	@DisplayName("claude_code/synthetic 사례")
	fun synthetic(): List<DynamicTest> = cases(suite.load("claude_code/synthetic"))

	@TestFactory
	@DisplayName("claude_code/real 사례 — 기대값이 있는 파일")
	fun real(): List<DynamicTest> = cases(suite.loadPaired("claude_code/real"))

	private fun cases(cases: List<FixtureCase>): List<DynamicTest> {
		assertThat(cases).isNotEmpty()
		return cases.map { case ->
			DynamicTest.dynamicTest(case.name) {
				val errors = suite.check(case)
				assertThat(errors).describedAs(errors.joinToString("\n", prefix = "\n")).isEmpty()
			}
		}
	}
}
