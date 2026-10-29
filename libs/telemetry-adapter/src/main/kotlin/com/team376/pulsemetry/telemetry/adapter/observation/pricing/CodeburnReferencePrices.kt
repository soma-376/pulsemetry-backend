package com.team376.pulsemetry.telemetry.adapter.observation.pricing

import com.team376.pulsemetry.telemetry.adapter.observation.EpochNanos
import java.math.BigDecimal
import java.time.Instant

/** Codeburn 0.9.24 출처를 포함한 단일 참조 카탈로그(ADR 0071). 실제 모델 동일성·청구액을 뜻하지 않는다. */
public object CodeburnReferencePrices {
	public val AS_OF: Instant = Instant.parse("2026-10-16T10:00:00Z")
	private val referenceTime = EpochNanos(AS_OF.epochSecond * 1_000_000_000L)

	/** 원본 모델명을 변경하지 않고 대체 환산 기준을 추적한다. */
	public data class Entry(val model: String, val referenceModel: String, val price: ModelPrice)

	public val ENTRIES: List<Entry> = loadCatalog()

	/** 기존에 직접 검증한 9개 모델의 요율·요청별 장문 정책이 우선이다. 요율을 변경하면 코드 리뷰와 배포를 거친다. */
	public val CATALOG: PricingCatalog = PricingCatalog(
		prices = buildMap {
			ENTRIES.forEach { put(it.model, it.price) }
			CurrentPublicPrices.PRICES.forEach { put(it.model, it) }
		}.values.toList(),
	)

	public val REFERENCE: ReferenceValuation = ReferenceValuation(
			asOf = referenceTime, currency = "USD", serviceTier = "standard", geography = "global",
			sources = listOf("https://github.com/getagentseal/codeburn/tree/v0.9.24") + CurrentPublicPrices.REFERENCE.sources,
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
					model = model,
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
