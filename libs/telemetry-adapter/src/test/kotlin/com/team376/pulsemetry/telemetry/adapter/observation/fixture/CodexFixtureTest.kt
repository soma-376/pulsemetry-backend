package com.team376.pulsemetry.telemetry.adapter.observation.fixture

import com.team376.pulsemetry.telemetry.adapter.observation.codex.CodexProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.ProfileRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Codex 프로파일의 수용 사례. `codex/synthetic/` 은 ADR 0020 부록 A 의 규칙을 드러내는 명세 사례이고,
 * `codex/real/` 은 실캡처 익명화 추출본에 명세에서 쓴 기대값을 붙인 것이다(기대값이 있는 파일만).
 */
class CodexFixtureTest {

	private val suite = FixtureSuite(ProfileRegistry(listOf(CodexProfile)))

	@TestFactory
	@DisplayName("codex/synthetic 사례")
	fun synthetic(): List<DynamicTest> = cases(suite.load("codex/synthetic"))

	@TestFactory
	@DisplayName("codex/real 사례 — 기대값이 있는 파일")
	fun real(): List<DynamicTest> = cases(suite.loadPaired("codex/real"))

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
