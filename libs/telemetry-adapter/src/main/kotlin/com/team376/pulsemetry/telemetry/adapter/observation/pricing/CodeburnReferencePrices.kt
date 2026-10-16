package com.team376.pulsemetry.telemetry.adapter.observation.pricing

import com.team376.pulsemetry.telemetry.adapter.observation.EpochNanos
import java.math.BigDecimal
import java.time.Instant

/** Codeburn 0.9.24 고정 참조 가격. 실제 모델 동일성·청구액이 아닌 사용자 지정 환산 기준(ADR 0065). */
public object CodeburnReferencePrices {
	public const val VERSION: String = "codeburn-0.9.24-reference-2026-10-16-v1"
	public val AS_OF: Instant = Instant.parse("2026-10-16T10:00:00Z")
	private val referenceTime = EpochNanos(AS_OF.epochSecond * 1_000_000_000L)

	/** 원본 모델명을 변경하지 않고 대체 환산 기준을 추적한다. */
	public data class Entry(val model: String, val referenceModel: String, val price: ModelPrice)

	public val ENTRIES: List<Entry> = loadCatalog()

	/** 기존에 직접 검증한 9개 모델의 요율·요청별 장문 정책이 우선이다. 과거 판 자체는 바꾸지 않는다. */
	public val PROFILE: PricingProfile = PricingProfile(
		pricingVersion = VERSION,
		basis = PricingBasis.CURRENT_REFERENCE,
		requireCacheCreateReported = true,
		prices = buildMap {
			ENTRIES.forEach { put(it.model, it.price) }
			CurrentPublicPrices.PROFILE.prices.forEach { put(it.model, it.copy(validFrom = referenceTime)) }
		}.values.toList(),
		reference = ReferenceValuation(
			asOf = referenceTime, currency = "USD", serviceTier = "standard", geography = "global",
			sources = listOf("https://github.com/getagentseal/codeburn/tree/v0.9.24") + CurrentPublicPrices.PROFILE.reference!!.sources,
		),
	)

	private fun loadCatalog(): List<Entry> {
		val stream = requireNotNull(javaClass.getResourceAsStream("/pricing/codeburn-0.9.24.tsv")) { "Codeburn 가격 카탈로그가 없다" }
		val entries = stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
			lines.drop(1).filter { it.isNotBlank() }.map { line ->
				val fields = line.split('\t')
				require(fields.size == 7) { "Codeburn 가격 행의 필드 수가 다르다" }
				val (model, referenceModel, family) = fields
				require(model.isNotBlank() && referenceModel.isNotBlank()) { "빈 모델 ID는 허용하지 않는다" }
				val semantics = when (family) {
					"claude" -> "claude-code-exclusive-v1"
					"codex" -> "codex-responses-inclusive-v1"
					else -> error("지원하지 않는 Codeburn 모델 종류다: $family")
				}
				Entry(model, referenceModel, ModelPrice(
					model = model, validFrom = referenceTime, validUntil = null,
					semanticsProfiles = setOf(semantics),
					inputUncachedPerMillion = BigDecimal(fields[3]), outputPerMillion = BigDecimal(fields[4]),
					cacheReadPerMillion = BigDecimal(fields[5]), cacheCreatePerMillion = BigDecimal(fields[6]),
					cacheCreateReferenceTtlSeconds = if (family == "claude") 300 else null,
				))
			}.toList()
		}
		require(entries.isNotEmpty() && entries.map { it.model }.distinct().size == entries.size) { "Codeburn 가격표가 비었거나 중복 모델이 있다" }
		return entries
	}
}
