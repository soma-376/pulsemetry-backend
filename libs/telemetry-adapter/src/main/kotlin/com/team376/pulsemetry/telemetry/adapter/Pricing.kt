package com.team376.pulsemetry.telemetry.adapter

/**
 * 모델별 토큰 단가표 — `cost_usd` 를 직접 주지 않는 툴(codex)의 비용 추정.
 *
 * ⚠️ **단가는 자리표시자다.** 실제 청구서·공식 가격표로 갱신해야 한다. 이식 원본이 그렇게
 * 적어 두었고 값을 바꾸면 golden fixture 의 `cost_usd` 가 전부 바뀐다 — 갱신은 별도 티켓이다.
 *
 * 단위는 100만 토큰당 USD 이고, 매칭은 **부분 문자열**이라 `gpt-5-codex-2026-...` 같은 이름도
 * 함께 걸린다. **[RATES] 의 선언 순서가 결과를 가른다** — `gpt-5-codex` 가 `gpt-5` 보다
 * 먼저 와야 한다.
 */
internal object Pricing {

	/** 모델 부분 문자열(소문자) → (input, output, cacheRead, cacheCreate) per 1M tokens. */
	private val RATES: List<Pair<String, Rates>> = listOf(
		// --- OpenAI Codex 계열 (⚠️ 확인 필요) ---
		"gpt-5-codex" to Rates(1.25, 10.0, 0.125, 1.25),
		"gpt-5" to Rates(1.25, 10.0, 0.125, 1.25),
		"o4-mini" to Rates(1.10, 4.40, 0.275, 1.10),
		"codex" to Rates(1.25, 10.0, 0.125, 1.25),
		// --- Anthropic Claude 계열 (참고용. Claude 는 보통 cost_usd 를 직접 준다) ---
		"opus" to Rates(15.0, 75.0, 1.50, 18.75),
		"sonnet" to Rates(3.0, 15.0, 0.30, 3.75),
		"haiku" to Rates(0.80, 4.0, 0.08, 1.0),
	)

	/** 미상 모델 폴백. */
	private val DEFAULT = Rates(1.25, 10.0, 0.125, 1.25)

	private class Rates(
		val input: Double,
		val output: Double,
		val cacheRead: Double,
		val cacheCreate: Double,
	)

	private fun ratesOf(model: String?): Rates {
		val lowered = model?.lowercase() ?: return DEFAULT
		for ((key, rates) in RATES) if (key in lowered) return rates
		return DEFAULT
	}

	/** 단가표로 비용(USD)을 추정한다. `cost_usd` 가 없을 때만 쓴다. */
	fun estimate(
		model: String?,
		input: Int,
		output: Int,
		cacheRead: Int,
		cacheCreate: Int,
	): Double {
		val rates = ratesOf(model)
		return (input * rates.input + output * rates.output +
			cacheRead * rates.cacheRead + cacheCreate * rates.cacheCreate) / 1_000_000
	}
}
