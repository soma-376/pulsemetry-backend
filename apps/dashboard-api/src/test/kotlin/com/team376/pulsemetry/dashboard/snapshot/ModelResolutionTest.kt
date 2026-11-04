package com.team376.pulsemetry.dashboard.snapshot

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ModelResolutionTest {
	@Test
	fun existingIdsRemainStableWithoutProviderInference() {
		val resolution = ModelResolution.NONE
		assertThat(resolution.version).isEqualTo("model-id-v1")
		assertThat(resolution.resolve("claude_code", "claude-sonnet-4").modelId).isEqualTo("unknown/claude_code/claude-sonnet-4")
		assertThat(resolution.resolve("codex", "gpt-5").modelId).isEqualTo("unknown/codex/gpt-5")
		assertThat(resolution.resolve("unknown", null).modelId).isEqualTo("unknown/unknown/~")
		assertThat(resolution.sql().provider).isEqualTo("CAST(NULL AS Nullable(String))")
	}

	@Test
	fun escapingIsInjective() {
		val ids = listOf("a/b", "a%2Fb", "~", null, "", "a~b", "a%7Eb")
			.map { ModelResolution.NONE.resolve("codex", it).modelId }
		assertThat(ids).doesNotHaveDuplicates()
		assertThat(ModelResolution.NONE.resolve("co/dex", "m").modelId).isEqualTo("unknown/co%2Fdex/m")
	}
}
