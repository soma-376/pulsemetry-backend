package com.team376.pulsemetry.telemetry.adapter.observation

import com.google.protobuf.Message

/** 파이프라인 한 번의 결과 — hash 가 봉인된 관측과 집계. */
public class ObservationBatch(
	public val events: List<EventObservation>,
	public val metricPoints: List<MetricPointObservation>,
	public val stats: NormalizationStats,
)

/**
 * 정규화 단계 전체(ADR 0020 §3 의 처리 순서): 매핑([ObservationNormalizer]) → `analysis_hash` 봉인.
 * 조직 보강과 적재는 이 뒤, 다음 모듈의 몫이다.
 */
public class ObservationPipeline(
	private val normalizer: ObservationNormalizer = ObservationNormalizer(),
) {
	public fun run(request: Message, context: ObservationContext): ObservationBatch {
		val result = normalizer.normalize(request, context)
		return ObservationBatch(
			events = result.events.map { AnalysisHash.seal(it.observation) },
			metricPoints = result.metricPoints.map { AnalysisHash.seal(it) },
			stats = result.stats,
		)
	}
}
