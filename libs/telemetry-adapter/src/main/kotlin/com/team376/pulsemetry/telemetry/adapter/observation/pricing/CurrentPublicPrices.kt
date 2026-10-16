package com.team376.pulsemetry.telemetry.adapter.observation.pricing

import com.team376.pulsemetry.telemetry.adapter.observation.EpochNanos
import java.math.BigDecimal
import java.time.Instant

/**
 * 공식 문서 조회 시각에 고정한 Standard/global 사용 환산 단가(허브 ADR 0008·0009, backend ADR 0060·0061).
 * 과거 실제 청구 가격이라고 주장하지 않는다. 변경은 새 ID와 정규화 판을 함께 만든다.
 * 근거·미지원 범위는 `docs/pricing/current-public-standard.md`에 있다.
 */
public object CurrentPublicPrices {
	public const val VERSION_V1: String = "public-standard-global-reference-2026-10-16-v1"
	public const val VERSION_V2: String = "public-standard-global-reference-2026-10-16-v2"
	public const val VERSION_V3: String = "public-standard-global-reference-2026-10-16-v3"
	public const val VERSION: String = "public-standard-global-reference-2026-10-16-v4"
	public val AS_OF_V1: Instant = Instant.parse("2026-10-15T23:02:00Z")
	public val AS_OF_V2: Instant = Instant.parse("2026-10-15T23:29:51Z")
	public val AS_OF_V3: Instant = Instant.parse("2026-10-16T06:59:11Z")
	public val AS_OF: Instant = Instant.parse("2026-10-16T08:03:52Z")
	private fun time(instant: Instant) = EpochNanos(instant.epochSecond * 1_000_000_000L + instant.nano)

	public val PROFILE_V1: PricingProfile = PricingProfile(
		pricingVersion = VERSION_V1,
		prices = listOf(
			price("claude-opus-5-5", "4", "20", "0.20", time(AS_OF_V1)),
			price("claude-opus-5", "5", "25", "0.50", time(AS_OF_V1)),
			price("claude-fable-5-1", "10", "50", "0.25", time(AS_OF_V1)),
			price("claude-haiku-4-5-20251001", "1", "5", "0.10", time(AS_OF_V1)),
		),
		basis = PricingBasis.CURRENT_REFERENCE,
		reference = ReferenceValuation(
			asOf = time(AS_OF_V1),
			currency = "USD", serviceTier = "standard", geography = "global",
			sources = listOf("https://platform.claude.com/docs/en/about-claude/pricing"),
		),
		requireCacheCreateReported = true,
	)

	public val PROFILE_V2: PricingProfile = PricingProfile(
		pricingVersion = VERSION_V2,
		basis = PricingBasis.CURRENT_REFERENCE,
		requireCacheCreateReported = true,
		prices = PROFILE_V1.prices.map { it.copy(validFrom = time(AS_OF_V2)) } + listOf(
			price("claude-sonnet-5-5", "2", "10", "0.10", time(AS_OF_V2)),
			price("claude-opus-4-8", "5", "25", "0.50", time(AS_OF_V2)),
		),
		reference = PROFILE_V1.reference!!.copy(
			asOf = time(AS_OF_V2),
			sources = PROFILE_V1.reference!!.sources + listOf(
				"https://platform.claude.com/docs/en/models/sonnet-5-5/overview",
				"https://platform.claude.com/docs/en/models/opus-4-8/overview",
			),
		),
	)

	/** ADR 0061: Claude 캐시 생성은 실제 TTL 판정과 분리한 5분 공시 기준으로 평가한다. */
	public val PROFILE_V3: PricingProfile = PricingProfile(
		pricingVersion = VERSION_V3,
		basis = PricingBasis.CURRENT_REFERENCE,
		requireCacheCreateReported = true,
		prices = PROFILE_V2.prices.map {
			it.copy(
				validFrom = time(AS_OF_V3),
				cacheCreatePerMillion = it.inputUncachedPerMillion * BigDecimal("1.25"),
				cacheCreateReferenceTtlSeconds = 300,
			)
		} + ModelPrice(
			model = "gpt-6-astra", validFrom = time(AS_OF_V3), validUntil = null,
			semanticsProfiles = setOf("codex-responses-inclusive-v1"),
			inputUncachedPerMillion = BigDecimal("10"), outputPerMillion = BigDecimal("50"),
			cacheReadPerMillion = BigDecimal("1"), cacheCreatePerMillion = BigDecimal("12.50"),
			longContext = LongContextPricing(272_000, BigDecimal("2"), BigDecimal("1.5")),
		),
		reference = PROFILE_V2.reference!!.copy(
			asOf = time(AS_OF_V3),
			sources = PROFILE_V2.reference!!.sources + listOf(
				"https://platform.claude.com/docs/en/build-with-claude/prompt-caching",
				"https://developers.openai.com/api/docs/models/gpt-6-astra",
				"https://developers.openai.com/api/docs/pricing",
			),
		),
	)

	/** ADR 0062: 정확한 Luna 모델의 공개 단가를 새 불변 판에 추가한다. */
	public val PROFILE: PricingProfile = PricingProfile(
		pricingVersion = VERSION,
		basis = PricingBasis.CURRENT_REFERENCE,
		requireCacheCreateReported = true,
		prices = PROFILE_V3.prices.map { it.copy(validFrom = time(AS_OF)) } + listOf(
			codexPrice("gpt-5.6-luna", "0.20", "1.20", "0.02", "0.25"),
			codexPrice("gpt-6-luna", "0.10", "0.50", "0.01", "0.125"),
		),
		reference = PROFILE_V3.reference!!.copy(
			asOf = time(AS_OF),
			sources = PROFILE_V3.reference!!.sources + listOf(
				"https://developers.openai.com/api/docs/models/gpt-5.6-luna",
				"https://developers.openai.com/api/docs/models/gpt-6-luna",
			),
		),
	)

	/** 명시적 none 외 오타는 조용히 null 프로파일로 바꾸지 않는다. */
	public fun select(name: String): PricingStage = when (name) {
		CodeburnReferencePrices.VERSION -> PricingStage(CodeburnReferencePrices.PROFILE)
		VERSION_V1 -> PricingStage(PROFILE_V1)
		VERSION_V2 -> PricingStage(PROFILE_V2)
		VERSION_V3 -> PricingStage(PROFILE_V3)
		VERSION -> PricingStage(PROFILE)
		"none" -> PricingStage.NONE
		else -> throw IllegalArgumentException("지원하지 않는 가격 프로파일이다: $name")
	}

	private fun codexPrice(model: String, input: String, output: String, read: String, create: String) = ModelPrice(
		model = model, validFrom = time(AS_OF), validUntil = null,
		semanticsProfiles = setOf("codex-responses-inclusive-v1"),
		inputUncachedPerMillion = BigDecimal(input), outputPerMillion = BigDecimal(output),
		cacheReadPerMillion = BigDecimal(read), cacheCreatePerMillion = BigDecimal(create),
		longContext = LongContextPricing(272_000, BigDecimal("2"), BigDecimal("1.5")),
	)

	private fun price(model: String, input: String, output: String, read: String, validFrom: EpochNanos) = ModelPrice(
		model = model, validFrom = validFrom, validUntil = null,
		semanticsProfiles = setOf("claude-code-exclusive-v1"),
		inputUncachedPerMillion = BigDecimal(input), outputPerMillion = BigDecimal(output), cacheReadPerMillion = BigDecimal(read),
		// v1/v2는 양수 cache creation을 가격 미확인으로 남긴 역사 정책을 보존한다.
		cacheCreatePerMillion = null,
	)
}
