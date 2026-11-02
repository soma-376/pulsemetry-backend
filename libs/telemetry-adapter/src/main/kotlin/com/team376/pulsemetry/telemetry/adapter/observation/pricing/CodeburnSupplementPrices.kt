package com.team376.pulsemetry.telemetry.adapter.observation.pricing

import java.math.BigDecimal

/** Codeburn 고정 커밋의 두 누락 모델만 보충한다. 출처·해시는 docs/pricing/codeburn-supplement.json. */
public object CodeburnSupplementPrices {
	public const val SOURCE: String = "https://github.com/getagentseal/codeburn/blob/1acea44b7decea1a0cdf8714ecbf2ec3e28c76b3/src/data/litellm-snapshot.json"

	public val PRICES: List<ModelPrice> = listOf(
		ModelPrice(
			model = "gpt-6.1-sol", semanticsProfiles = setOf("codex-responses-inclusive-v1"),
			inputUncachedPerMillion = BigDecimal("2"), outputPerMillion = BigDecimal("10"),
			cacheReadPerMillion = BigDecimal("0.10"), cacheCreatePerMillion = BigDecimal("2.50"),
			longContext = LongContextPricing(272_000, BigDecimal("2"), BigDecimal("1.5")),
		),
		ModelPrice(
			model = "claude-haiku-5-5", semanticsProfiles = setOf("claude-code-exclusive-v1"),
			inputUncachedPerMillion = BigDecimal("0.10"), outputPerMillion = BigDecimal("0.50"),
			cacheReadPerMillion = BigDecimal("0.01"), cacheCreatePerMillion = BigDecimal("0.125"),
			cacheCreateReferenceTtlSeconds = 300,
			longContext = LongContextPricing(100_000, BigDecimal("5"), BigDecimal("5")),
		),
	)
}
