package com.team376.pulsemetry.telemetry.adapter.observation.fixture

import com.team376.pulsemetry.telemetry.adapter.observation.codex.CodexCompletedProfile
import com.team376.pulsemetry.telemetry.adapter.observation.codex.CodexProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.ProfileRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** 실캡처와 wire·버전 경계 사례. 완료 토큰 합계는 ADR 0061 산식으로 대조하며 가격 단계는 NONE이다. */
class CodexCompletedFixtureTest {

	private val suite = FixtureSuite(ProfileRegistry(listOf(CodexProfile, CodexCompletedProfile)))

	@Test
	fun unverifiedSignalsStayGeneric() {
		assertThat(CodexCompletedProfile.spans).isNull()
		assertThat(CodexCompletedProfile.metrics).isNull()
		assertThat(CodexCompletedProfile.logs.allowlist.record).doesNotContain("user.email", "user.account_id", "prompt", "error.message")
		assertThat(CodexCompletedProfile.logs.keepsEventName).isFalse()
	}

	@TestFactory
	fun completed(): List<DynamicTest> = suite.load("codex/completed").map { case ->
		DynamicTest.dynamicTest(case.name) {
			val errors = suite.check(case)
			assertThat(errors).describedAs(errors.joinToString("\n")).isEmpty()
		}
	}
}
