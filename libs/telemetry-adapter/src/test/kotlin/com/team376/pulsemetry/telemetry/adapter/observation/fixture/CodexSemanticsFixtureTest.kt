package com.team376.pulsemetry.telemetry.adapter.observation.fixture

import com.team376.pulsemetry.telemetry.adapter.observation.codex.CodexProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.ProfileRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** ADR 0061의 토큰 포함관계·미보고·잘못된 값·버전별 완료 emitter 경계를 검증한다. */
class CodexSemanticsFixtureTest {

	private val suite = FixtureSuite(ProfileRegistry(listOf(CodexProfile)))

	@TestFactory
	fun responsesUsage(): List<DynamicTest> = suite.load("codex/semantics").map { case ->
		DynamicTest.dynamicTest(case.name) {
			val errors = suite.check(case)
			assertThat(errors).describedAs(errors.joinToString("\n")).isEmpty()
		}
	}
}
