package com.team376.pulsemetry.telemetry.adapter.observation.fixture

import com.team376.pulsemetry.telemetry.adapter.observation.claudecode.ClaudeApiProfile
import com.team376.pulsemetry.telemetry.adapter.observation.claudecode.ClaudeCodeProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.ProfileRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** 실제 8요청의 원시 성분으로 계산한 총계와 버전·이벤트·wire 경계를 고정한다. */
class ClaudeApiFixtureTest {

	private val suite = FixtureSuite(ProfileRegistry(listOf(ClaudeCodeProfile, ClaudeApiProfile)))

	@Test
	fun unverifiedSignalsStayGeneric() {
		assertThat(ClaudeApiProfile.spans).isNull()
		assertThat(ClaudeApiProfile.metrics).isNull()
		assertThat(ClaudeApiProfile.logs.allowlist.record).doesNotContain("user.email", "user.id", "prompt", "error")
		assertThat(ClaudeApiProfile.logs.keepsEventName).isFalse()
		assertThat(ClaudeCodeProfile.VERIFIED_VERSIONS).doesNotContain("2.1.290")
	}

	@TestFactory
	fun api(): List<DynamicTest> = suite.load("claude_code/api-2.1.290").map { case ->
		DynamicTest.dynamicTest(case.name) {
			val errors = suite.check(case)
			assertThat(errors).describedAs(errors.joinToString("\n")).isEmpty()
		}
	}
}
