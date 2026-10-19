package com.team376.pulsemetry.telemetry.adapter.observation.pricing

import com.team376.pulsemetry.telemetry.adapter.observation.EpochNanos
import com.team376.pulsemetry.telemetry.adapter.observation.EventObservation
import com.team376.pulsemetry.telemetry.adapter.observation.EventType
import com.team376.pulsemetry.telemetry.adapter.observation.OutputSemantics
import java.math.BigDecimal

/** 사건 당시 요금과 현재 공시 표준 환산의 기준 시각을 구분한다(허브 ADR 0008). */
public enum class PricingBasis { EVENT_TIME, CURRENT_REFERENCE }

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
	val validFrom: EpochNanos,
	/** 이 시각 이전까지(반개방). null 이면 끝이 없다. */
	val validUntil: EpochNanos?,
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
		require(validUntil == null || validUntil > validFrom) { "유효 기간이 비어 있다" }
		require(cacheCreateReferenceTtlSeconds == null || cacheCreateReferenceTtlSeconds > 0) { "캐시 생성 참조 TTL은 양수다" }
		require(cacheCreateReferenceTtlSeconds == null || cacheCreatePerMillion != null) { "캐시 생성 참조 TTL에는 단가가 필요하다" }
	}

	internal fun covers(time: EpochNanos): Boolean = time >= validFrom && (validUntil == null || time < validUntil)

	internal companion object {
		const val MAX_RATE_SCALE: Int = 6
	}
}

/**
 * 버전이 고정된 불변 가격 프로파일(ADR 0020 §4). `pricing_version` 이 [pricingVersion] 이다. 가격 데이터가 바뀌면
 * 새 버전이고 `normalizer_rev` 를 올린다. 통화는 USD 뿐이다 — USD 가 아닌 금액을 USD 컬럼에 넣지 않는다.
 */
public class PricingProfile(
	public val pricingVersion: String,
	public val prices: List<ModelPrice>,
	public val basis: PricingBasis = PricingBasis.EVENT_TIME,
	public val reference: ReferenceValuation? = null,
	/** 현재 카탈로그에서 cache creation 미보고를 0으로 간주하지 않는다. */
	public val requireCacheCreateReported: Boolean = false,
) {
	init {
		require(pricingVersion.isNotBlank()) { "가격 프로파일에는 버전이 있다" }
		require((basis == PricingBasis.CURRENT_REFERENCE) == (reference != null)) { "현재 기준에는 고정된 환산 근거가 필요하다" }
	}
}

/**
 * 가격 단계 — Pulsemetry 추정 비용(`cost_estimated_usd`·`pricing_version`)을 계산한다(ADR 0020 §4).
 *
 * 정규화 매핑 **뒤**, `analysis_hash` 봉인 **앞**에 돈다. 외부 조회를 하지 않고 주입된 [PricingProfile] 만으로
 * 결정적으로 계산한다. 이 모듈에 두는 이유는 hash 가 가격 결과를 재료로 삼기 때문이다 — 결과가 결정적이고 규칙의
 * 버전(`normalizer_rev`)에 묶여야 한다. 어떤 프로파일을 주입할지는 조립 앱이 정한다.
 *
 * **기본은 가격 프로파일 없음이다** — 추정 비용과 `pricing_version` 이 전부 null 이다. 계산하는 조건은 전부 충족돼야
 * 한다: 사용량 관측, 모델의 정확한 단가가 선택한 기준 시각에 유효하고 **하나뿐**, 관측의 의미 프로파일이 그 단가의
 * 전제에 있음, 파생 `tokens_input_uncached`·output·cache read 가 있음(cache write 가 보고됐다면 그 단가도).
 * 총토큰에 단가 하나를 곱하지 않고, 모르는 모델에 기본 단가를 쓰지 않는다. reasoning·tool 이 output 밖인 관측은
 * 그 단가가 없으므로 계산하지 않는다. 기존 모드는 source_time, 현재 기준 모드는 고정된 reference.asOf를 쓴다.
 * 선택한 모델의 긴 입력 요금이 있으면 비중복 입력 성분의 합으로 요청별 경계를 판정한다.
 */
public class PricingStage(private val profile: PricingProfile?) {

	public fun apply(observation: EventObservation): EventObservation {
		val estimated = profile?.let { estimate(observation, it) }
		return observation.copy(costEstimatedUsd = estimated, pricingVersion = estimated?.let { profile.pricingVersion })
	}

	private fun estimate(o: EventObservation, profile: PricingProfile): BigDecimal? {
		if (o.eventType != EventType.MODEL_RESPONSE_USAGE) return null
		if (o.outputSemantics != OutputSemantics.INCLUSIVE_REASONING_TOOL) return null
		val model = o.envelope.model ?: return null
		val semantics = o.semanticsProfile ?: return null
		val valuationTime = profile.reference?.asOf ?: o.envelope.sourceTime
		val candidates = profile.prices.filter { it.model == model && it.covers(valuationTime) && semantics in it.semanticsProfiles }
		val price = candidates.singleOrNull() ?: return null
		if (profile.requireCacheCreateReported && o.tokensCacheCreate == null) return null

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
			// 명시 0은 항상 0이다. 양수는 선택한 프로파일에 명시된 평가 단가가 있어야 한다.
			if (create != 0L) {
				val rate = price.cacheCreatePerMillion ?: return null
				micros += BigDecimal.valueOf(create) * rate * inputMultiplier
			}
		}
		return micros.movePointLeft(6)
	}

	public companion object {
		/** 가격 프로파일 없음 — 추정 비용은 전부 null 이다. */
		public val NONE: PricingStage = PricingStage(null)
	}
}
