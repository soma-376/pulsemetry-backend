package com.team376.pulsemetry.telemetry.adapter

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 단가표 — **선언 순서가 결과를 가른다.**
 *
 * ⚠️ 단가 자체는 자리표시자다. 값을 바꾸면 golden fixture 의 `cost_usd` 가 전부 바뀐다.
 */
internal class PricingTest {

	@Test
	@DisplayName("부분 문자열 매칭이라 더 긴 이름이 먼저 선언돼야 한다")
	fun longerKeysMustComeFirst() {
		// gpt-5-codex 와 gpt-5 는 지금 같은 단가지만, 순서가 뒤집히면 앞으로 값이 갈릴 때
		// 조용히 틀린 단가를 쓴다. 두 이름이 서로 다른 항목에 걸린다는 사실을 고정한다.
		val codex = Pricing.estimate("gpt-5-codex-2026-01-01", 1_000_000, 0, 0, 0)
		val gpt5 = Pricing.estimate("gpt-5", 1_000_000, 0, 0, 0)
		val mini = Pricing.estimate("o4-mini", 1_000_000, 0, 0, 0)

		assertThat(codex).isEqualTo(1.25)
		assertThat(gpt5).isEqualTo(1.25)
		assertThat(mini).isEqualTo(1.10)
	}

	@Test
	@DisplayName("대소문자를 가리지 않고 미상 모델은 기본 단가로 떨어진다")
	fun unknownModelsFallBack() {
		assertThat(Pricing.estimate("CLAUDE-SONNET-4-5", 1_000_000, 0, 0, 0)).isEqualTo(3.0)
		assertThat(Pricing.estimate("who-knows", 1_000_000, 0, 0, 0)).isEqualTo(1.25)
		assertThat(Pricing.estimate(null, 1_000_000, 0, 0, 0)).isEqualTo(1.25)
	}

	@Test
	@DisplayName("네 칸을 각각의 단가로 더한다")
	fun sumsTheFourBillableBuckets() {
		// reasoning·tool 은 애초에 인자에 없다 — 더하면 이중계산이다.
		val cost = Pricing.estimate("gpt-5-codex", 1_000_000, 1_000_000, 1_000_000, 1_000_000)
		assertThat(cost).isEqualTo(1.25 + 10.0 + 0.125 + 1.25)
	}
}
