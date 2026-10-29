package com.team376.pulsemetry.telemetry.adapter.observation.pricing

import com.team376.pulsemetry.telemetry.adapter.observation.EpochNanos
import java.math.BigDecimal
import java.time.Instant

/** 단일 참조 카탈로그에서 우선하는 공식 확인 단가. 조회 시각은 출처 근거이며 가격 선택 조건이 아니다. */
public object CurrentPublicPrices {
	public val REFERENCE: ReferenceValuation = ReferenceValuation(
		asOf = EpochNanos.of(Instant.parse("2026-10-16T08:03:52Z")),
		currency = "USD", serviceTier = "standard", geography = "global",
		sources = listOf(
			"https://platform.claude.com/docs/en/about-claude/pricing",
			"https://platform.claude.com/docs/en/models/sonnet-5-5/overview",
			"https://platform.claude.com/docs/en/models/opus-4-8/overview",
			"https://platform.claude.com/docs/en/build-with-claude/prompt-caching",
			"https://developers.openai.com/api/docs/models/gpt-6-astra",
			"https://developers.openai.com/api/docs/pricing",
			"https://developers.openai.com/api/docs/models/gpt-5.6-luna",
			"https://developers.openai.com/api/docs/models/gpt-6-luna",
		),
	)

	public val PRICES: List<ModelPrice> = listOf(
		claudePrice("claude-opus-5-5", "4", "20", "0.20"),
		claudePrice("claude-opus-5", "5", "25", "0.50"),
		claudePrice("claude-fable-5-1", "10", "50", "0.25"),
		claudePrice("claude-haiku-4-5-20251001", "1", "5", "0.10"),
		claudePrice("claude-sonnet-5-5", "2", "10", "0.10"),
		claudePrice("claude-opus-4-8", "5", "25", "0.50"),
		codexPrice("gpt-6-astra", "10", "50", "1", "12.50"),
		codexPrice("gpt-5.6-luna", "0.20", "1.20", "0.02", "0.25"),
		codexPrice("gpt-6-luna", "0.10", "0.50", "0.01", "0.125"),
	)

	private fun codexPrice(model: String, input: String, output: String, read: String, create: String) = ModelPrice(
		model = model, semanticsProfiles = setOf("codex-responses-inclusive-v1"),
		inputUncachedPerMillion = BigDecimal(input), outputPerMillion = BigDecimal(output),
		cacheReadPerMillion = BigDecimal(read), cacheCreatePerMillion = BigDecimal(create),
		longContext = LongContextPricing(272_000, BigDecimal("2"), BigDecimal("1.5")),
	)

	private fun claudePrice(model: String, input: String, output: String, read: String) = ModelPrice(
		model = model, semanticsProfiles = setOf("claude-code-exclusive-v1"),
		inputUncachedPerMillion = BigDecimal(input), outputPerMillion = BigDecimal(output), cacheReadPerMillion = BigDecimal(read),
		cacheCreatePerMillion = BigDecimal(input) * BigDecimal("1.25"), cacheCreateReferenceTtlSeconds = 300,
	)
}
