package com.team376.pulsemetry.telemetry.adapter.observation.pricing

import com.team376.pulsemetry.telemetry.adapter.observation.EpochNanos
import com.team376.pulsemetry.telemetry.adapter.observation.EventObservation
import com.team376.pulsemetry.telemetry.adapter.observation.EventType
import com.team376.pulsemetry.telemetry.adapter.observation.OutputSemantics
import com.team376.pulsemetry.telemetry.adapter.observation.QualityFlag
import java.math.BigDecimal

/** 실제 계정의 청구 조건이 아니라 출처를 명시한 참조 단가 평가의 고정 기준이다. */
public data class ReferenceValuation(
	val asOf: EpochNanos,
	val currency: String,
	val serviceTier: String,
	val geography: String,
	val sources: List<String>,
) {
	init {
		require(currency == "USD" && serviceTier == "standard" && geography == "global") { "지원하는 환산 기준은 Standard/global/USD다" }
		require(sources.isNotEmpty() && sources.all { it.startsWith("https://") }) { "참조 출처 URL이 필요하다" }
	}
}

/** 한 요청의 전체 입력이 경계를 초과할 때 적용하는 공시 단가 배수다. */
public data class LongContextPricing(
	val inputTokenThreshold: Long,
	val inputAndCacheMultiplier: BigDecimal,
	val outputMultiplier: BigDecimal,
) {
	init {
		require(inputTokenThreshold >= 0) { "입력 토큰 경계는 음수가 아니다" }
		require(inputAndCacheMultiplier >= BigDecimal.ONE && outputMultiplier >= BigDecimal.ONE) { "긴 입력 단가 배수는 1 이상이다" }
	}
}

/**
 * 모델 하나의 참조 단가(USD / 100만 토큰). [semanticsProfiles] 는 이 단가가 전제하는 토큰 의미 프로파일이다 —
 * 성분의 뜻이 확인된 관측에만 곱한다.
 */
public data class ModelPrice(
	/** `model` 컬럼과 **정확히** 같은 값. 부분 문자열·접두사로 맞추지 않는다. */
	val model: String,
	val semanticsProfiles: Set<String>,
	val inputUncachedPerMillion: BigDecimal,
	val outputPerMillion: BigDecimal,
	val cacheReadPerMillion: BigDecimal,
	/** cache write 단가. cache write 가 보고되는 관측에 필요하다. */
	val cacheCreatePerMillion: BigDecimal?,
	/** 현재 참조 평가의 캐시 생성 TTL 기준이다. 원천에서 관측한 TTL이 아니다. */
	val cacheCreateReferenceTtlSeconds: Int? = null,
	val longContext: LongContextPricing? = null,
) {
	init {
		val rates = listOfNotNull(inputUncachedPerMillion, outputPerMillion, cacheReadPerMillion, cacheCreatePerMillion)
		val longContextRates = longContext?.let { rule ->
			listOfNotNull(inputUncachedPerMillion, cacheReadPerMillion, cacheCreatePerMillion).map { it * rule.inputAndCacheMultiplier } +
				(outputPerMillion * rule.outputMultiplier)
		}.orEmpty()
		for (rate in rates + longContextRates) {
			require(rate.signum() >= 0) { "단가는 음수가 아니다" }
			// 100만으로 나눠도 Decimal(38, 12) 에 정확히 들어가야 한다 — 절삭하지 않는다.
			require(rate.stripTrailingZeros().scale() <= MAX_RATE_SCALE) { "단가의 소수 자릿수는 $MAX_RATE_SCALE 이하다: $rate" }
		}
		require(cacheCreateReferenceTtlSeconds == null || cacheCreateReferenceTtlSeconds > 0) { "캐시 생성 참조 TTL은 양수다" }
		require(cacheCreateReferenceTtlSeconds == null || cacheCreatePerMillion != null) { "캐시 생성 참조 TTL에는 단가가 필요하다" }
	}

	internal companion object {
		const val MAX_RATE_SCALE: Int = 6
	}
}

/** 단일 참조 단가표. 출처는 카탈로그 수준에 두며 관측마다 가격판 식별자를 기록하지 않는다(ADR 0071). */
public class PricingCatalog(public val prices: List<ModelPrice>)

/**
 * 정규화 매핑 뒤·analysis_hash 봉인 앞에서 비중복 성분별 USD 참조 금액을 계산한다.
 * 사건 시각별 가격 선택이나 실행 중 조회는 하지 않는다. 정확한 모델과 검증된 의미가 하나의 단가와
 * 일치하고 uncached input·output·cache read·cache create가 모두 있을 때만 계산한다.
 * 모르는 모델·의미·성분을 추정하거나 실제 청구액과 합치지 않는다.
 */
public class PricingStage(private val catalog: PricingCatalog = CodeburnReferencePrices.CATALOG) {

	public fun apply(observation: EventObservation): EventObservation =
		observation.copy(costEstimatedUsd = estimate(observation))

	private fun estimate(o: EventObservation): BigDecimal? {
		if (o.eventType != EventType.MODEL_RESPONSE_USAGE) return null
		if (QualityFlag.DERIVED_VALUE_INVALID in o.envelope.qualityFlags) return null
		if (o.outputSemantics != OutputSemantics.INCLUSIVE_REASONING_TOOL) return null
		val model = o.envelope.model ?: return null
		val semantics = o.semanticsProfile ?: return null
		val price = catalog.prices.singleOrNull { it.model == model && semantics in it.semanticsProfiles } ?: return null
		if (o.tokensCacheCreate == null) return null

		val uncached = o.tokensInputUncached ?: return null
		val output = o.tokensOutput ?: return null
		val read = o.tokensCacheRead ?: return null
		val longContext = price.longContext?.takeIf { rule ->
			// 파생된 비중복 입력 성분의 합이다. 미보고를 0으로 채우거나 Long 합산을 넘치게 하지 않는다.
			val create = o.tokensCacheCreate ?: return null
			val promptTokens = BigDecimal.valueOf(uncached) + BigDecimal.valueOf(read) + BigDecimal.valueOf(create)
			promptTokens > BigDecimal.valueOf(rule.inputTokenThreshold)
		}
		val inputMultiplier = longContext?.inputAndCacheMultiplier ?: BigDecimal.ONE
		val outputMultiplier = longContext?.outputMultiplier ?: BigDecimal.ONE
		var micros = BigDecimal.valueOf(uncached) * price.inputUncachedPerMillion * inputMultiplier +
			BigDecimal.valueOf(output) * price.outputPerMillion * outputMultiplier +
			BigDecimal.valueOf(read) * price.cacheReadPerMillion * inputMultiplier
		o.tokensCacheCreate?.let { create ->
			// 명시 0은 항상 0이다. 양수는 카탈로그에 명시된 평가 단가가 있어야 한다.
			if (create != 0L) {
				val rate = price.cacheCreatePerMillion ?: return null
				micros += BigDecimal.valueOf(create) * rate * inputMultiplier
			}
		}
		return micros.movePointLeft(6)
	}

}
