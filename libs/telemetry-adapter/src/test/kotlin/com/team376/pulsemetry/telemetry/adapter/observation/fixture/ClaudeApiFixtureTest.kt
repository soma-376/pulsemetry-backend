package com.team376.pulsemetry.telemetry.adapter.observation.fixture

import com.team376.pulsemetry.telemetry.adapter.observation.claudecode.ClaudeCodeProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.ProfileRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** 실제 8요청의 원시 성분으로 계산한 총계와 ADR 0074의 이벤트·wire 경계를 고정한다. */
class ClaudeApiFixtureTest {

	private val suite = FixtureSuite(ProfileRegistry(listOf(ClaudeCodeProfile)))

	@Test
	fun consolidatedProfileKeepsContentOutOfMetadata() {
		assertThat(ClaudeCodeProfile.spans).isNotNull()
		assertThat(ClaudeCodeProfile.metrics).isNotNull()
		assertThat(ClaudeCodeProfile.logs.allowlist.record).doesNotContain("user.email", "user.id", "prompt", "error")
		assertThat(ClaudeCodeProfile.logs.keepsEventName).isFalse()
	}

	@TestFactory
	fun api(): List<DynamicTest> = suite.load("claude_code/api-2.1.290").map { case ->
		DynamicTest.dynamicTest(case.name) {
			val errors = suite.check(case)
			assertThat(errors).describedAs(errors.joinToString("\n")).isEmpty()
		}
	}
}
