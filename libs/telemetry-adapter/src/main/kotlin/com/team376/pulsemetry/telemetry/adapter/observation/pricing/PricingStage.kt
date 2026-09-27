package com.team376.pulsemetry.telemetry.adapter.observation.pricing

import com.team376.pulsemetry.telemetry.adapter.observation.EpochNanos
import com.team376.pulsemetry.telemetry.adapter.observation.EventObservation
import com.team376.pulsemetry.telemetry.adapter.observation.EventType
import com.team376.pulsemetry.telemetry.adapter.observation.OutputSemantics
import java.math.BigDecimal

/**
 * 모델 하나의 공시 단가(USD / 100만 토큰). [semanticsProfiles] 는 이 단가가 전제하는 토큰 의미 프로파일이다 —
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
) {
	init {
		for (rate in listOfNotNull(inputUncachedPerMillion, outputPerMillion, cacheReadPerMillion, cacheCreatePerMillion)) {
			require(rate.signum() >= 0) { "단가는 음수가 아니다" }
			// 100만으로 나눠도 Decimal(38, 12) 에 정확히 들어가야 한다 — 절삭하지 않는다.
			require(rate.stripTrailingZeros().scale() <= MAX_RATE_SCALE) { "단가의 소수 자릿수는 $MAX_RATE_SCALE 이하다: $rate" }
		}
		require(validUntil == null || validUntil > validFrom) { "유효 기간이 비어 있다" }
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
) {
	init {
		require(pricingVersion.isNotBlank()) { "가격 프로파일에는 버전이 있다" }
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
 * 한다: 사용량 관측, 모델의 정확한 단가가 source_time 에 유효하고 **하나뿐**, 관측의 의미 프로파일이 그 단가의
 * 전제에 있음, 파생 `tokens_input_uncached`·output·cache read 가 있음(cache write 가 보고됐다면 그 단가도).
 * 총토큰에 단가 하나를 곱하지 않고, 모르는 모델에 기본 단가를 쓰지 않는다. reasoning·tool 이 output 밖인 관측은
 * 그 단가가 없으므로 계산하지 않는다.
 */
public class PricingStage(private val profile: PricingProfile?) {

	public fun apply(observation: EventObservation): EventObservation {
		val estimated = profile?.let { estimate(observation, it) }
		return observation.copy(costEstimatedUsd = estimated, pricingVersion = estimated?.let { profile.pricingVersion })
	}

	private fun estimate(o: EventObservation, profile: PricingProfile): BigDecimal? {
		if (o.eventType != EventType.MODEL_RESPONSE_USAGE) return null
		if (o.outputSemantics == OutputSemantics.EXCLUSIVE_REASONING_TOOL) return null
		val model = o.envelope.model ?: return null
		val semantics = o.semanticsProfile ?: return null
		val candidates = profile.prices.filter { it.model == model && it.covers(o.envelope.sourceTime) && semantics in it.semanticsProfiles }
		val price = candidates.singleOrNull() ?: return null

		val uncached = o.tokensInputUncached ?: return null
		val output = o.tokensOutput ?: return null
		val read = o.tokensCacheRead ?: return null
		var micros = BigDecimal.valueOf(uncached) * price.inputUncachedPerMillion +
			BigDecimal.valueOf(output) * price.outputPerMillion +
			BigDecimal.valueOf(read) * price.cacheReadPerMillion
		o.tokensCacheCreate?.let { create ->
			val rate = price.cacheCreatePerMillion ?: return null
			micros += BigDecimal.valueOf(create) * rate
		}
		return micros.movePointLeft(6)
	}

	public companion object {
		/** 가격 프로파일 없음 — 추정 비용은 전부 null 이다. */
		public val NONE: PricingStage = PricingStage(null)
	}
}
