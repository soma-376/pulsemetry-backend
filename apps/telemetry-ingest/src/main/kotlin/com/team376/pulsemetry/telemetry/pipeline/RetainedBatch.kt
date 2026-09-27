package com.team376.pulsemetry.telemetry.pipeline

import com.team376.pulsemetry.persistence.telemetry.AnalysisWriteBoundary
import com.team376.pulsemetry.telemetry.adapter.observation.EpochNanos
import com.team376.pulsemetry.telemetry.adapter.observation.NormalizationStats
import com.team376.pulsemetry.telemetry.enricher.observation.EnrichedBatch
import com.team376.pulsemetry.telemetry.enricher.observation.EnrichedEvent
import com.team376.pulsemetry.telemetry.enricher.observation.EnrichedMetricPoint

/**
 * 문서 하나의 적재 결과 — 정규화 집계와, 삭제 경계로 뺀 관측 수, 실제로 적재한 관측의 `source_time` 범위(ADR 0024 §6).
 * ledger 행이 이것을 옮긴다.
 */
data class LoadedPart(
	val stats: NormalizationStats,
	val beforeBoundary: Int,
	val sourceTimeMin: EpochNanos?,
	val sourceTimeMax: EpochNanos?,
)

/** 보강 결과 하나에서 삭제 경계 이전 관측을 뺀 것. sink 도 같은 경계로 다시 거르지만, 문서별로 센 것은 여기뿐이다. */
class RetainedBatch private constructor(
	val events: List<EnrichedEvent>,
	val metricPoints: List<EnrichedMetricPoint>,
	val loaded: LoadedPart,
) {
	companion object {
		fun of(batch: EnrichedBatch, boundary: AnalysisWriteBoundary): RetainedBatch {
			val events = batch.events.filter { boundary.admits(it.observation.envelope.sourceTime) }
			val points = batch.metricPoints.filter { boundary.admits(it.observation.envelope.sourceTime) }
			val dropped = (batch.events.size - events.size) + (batch.metricPoints.size - points.size)
			if (dropped == 0) {
				return RetainedBatch(events, points, LoadedPart(batch.stats, 0, batch.stats.sourceTimeMin, batch.stats.sourceTimeMax))
			}
			// 경계는 범위를 좁히기만 한다 — 남은 관측에서 다시 잰다.
			val times = events.map { it.observation.envelope.sourceTime } + points.map { it.observation.envelope.sourceTime }
			return RetainedBatch(events, points, LoadedPart(batch.stats, dropped, times.minOrNull(), times.maxOrNull()))
		}
	}
}
