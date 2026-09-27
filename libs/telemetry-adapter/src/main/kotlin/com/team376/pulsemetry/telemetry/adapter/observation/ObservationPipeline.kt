package com.team376.pulsemetry.telemetry.adapter.observation

import com.google.protobuf.Message
import com.team376.pulsemetry.telemetry.adapter.observation.pricing.PricingStage
import com.team376.pulsemetry.telemetry.adapter.observation.semantics.TokenDerivation

/** 파이프라인 한 번의 결과 — hash 가 봉인된 관측과 집계. */
public class ObservationBatch(
	public val events: List<EventObservation>,
	public val metricPoints: List<MetricPointObservation>,
	public val stats: NormalizationStats,
)

/**
 * 정규화 단계 전체(ADR 0020 §3 의 처리 순서): 매핑([ObservationNormalizer]) → 파생 토큰([TokenDerivation]) →
 * 가격([PricingStage]) → `analysis_hash` 봉인. 조직 보강과 적재는 이 뒤, 다음 모듈의 몫이다.
 *
 * 가격 프로파일의 기본은 없음이다 — 추정 비용은 null 이다.
 */
public class ObservationPipeline(
	private val normalizer: ObservationNormalizer = ObservationNormalizer(),
	private val pricing: PricingStage = PricingStage.NONE,
) {
	public fun run(request: Message, context: ObservationContext): ObservationBatch {
		val result = normalizer.normalize(request, context)
		return ObservationBatch(
			events = result.events.map { event ->
				AnalysisHash.seal(pricing.apply(TokenDerivation.apply(event.observation, event.semantics)))
			},
			metricPoints = result.metricPoints.map { AnalysisHash.seal(it) },
			stats = result.stats,
		)
	}
}
