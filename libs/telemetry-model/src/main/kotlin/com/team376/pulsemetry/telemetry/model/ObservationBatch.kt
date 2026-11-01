package com.team376.pulsemetry.telemetry.model

/** 파이프라인 한 번의 결과 — hash 가 봉인된 관측과 집계. */
public class ObservationBatch(
	public val events: List<EventObservation>,
	public val metricPoints: List<MetricPointObservation>,
	public val stats: NormalizationStats,
)
