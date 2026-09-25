package com.team376.pulsemetry.dashboard.snapshot

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ModelResolutionTest {

	@Test
	@DisplayName("운영 판은 공급자를 정하지 않는다 — product 로 추정하지 않는다")
	fun productionHasNoProvider() {
		for (product in listOf("codex", "claude_code", "unknown")) {
			assertThat(ModelResolution.NONE.resolve(product, null, null, "m").provider).isNull()
		}
	}

	@Test
	@DisplayName("escape 는 서로 다른 입력을 같은 ID 로 만들지 않는다")
	fun escapingIsInjective() {
		val ids = listOf("a/b", "a%2Fb", "~", null, "", "a~b", "a%7Eb")
			.map { ModelResolution.NONE.resolve("codex", null, null, it).modelId }

		assertThat(ids).doesNotHaveDuplicates()
		assertThat(ModelResolution.NONE.resolve("co/dex", null, null, "m").modelId).isEqualTo("unknown/co%2Fdex/m")
	}

	@Test
	@DisplayName("범위가 겹치거나 범위 없는 공급자의 별칭 표는 만들 수 없다")
	fun inconsistentTablesAreRejected() {
		val scope = ModelResolution.ProviderScope("claude_code", "claude-code", setOf("1"), "anthropic")
		assertThatThrownBy {
			ModelResolution("v", listOf(scope, scope.copy(provider = "other")), emptyMap())
		}.isInstanceOf(IllegalArgumentException::class.java)
		assertThatThrownBy {
			ModelResolution("v", listOf(scope), mapOf("openai" to mapOf("a" to "b")))
		}.isInstanceOf(IllegalArgumentException::class.java)
	}

	@Test
	@DisplayName("공급자가 있으면 별칭 표에 없는 모델도 원래 이름으로 남는다")
	fun unregisteredModelOfKnownProvider() {
		val resolution = ModelResolution(
			"v",
			listOf(ModelResolution.ProviderScope("claude_code", "claude-code", setOf("1"), "anthropic")),
			mapOf("anthropic" to mapOf("alias" to "canonical")),
		)

		assertThat(resolution.resolve("claude_code", "claude-code", "1", "new-model").modelId).isEqualTo("anthropic/new-model")
		assertThat(resolution.resolve("claude_code", "claude-code", "1", null).modelId).isEqualTo("anthropic/~")
	}
}
