package com.team376.pulsemetry.telemetry.enricher.observation

import com.team376.pulsemetry.telemetry.adapter.observation.EventObservation
import com.team376.pulsemetry.telemetry.adapter.observation.MetricPointObservation
import com.team376.pulsemetry.telemetry.adapter.observation.NormalizationStats
import com.team376.pulsemetry.telemetry.adapter.observation.OrgAttribution

/**
 * 조직 보강을 마친 이벤트 관측. 봉투 49컬럼 중 47이 여기 있다 — 적재가 `row_version` 둘을 더한다(ADR 0020 §1).
 *
 * [observation] 은 보강 플래그(`member_unresolved`·`multi_team_membership`)만 더해진 사본이다. `observation_id` 와
 * `analysis_hash` 는 정규화 단계의 값 그대로다 — 보강은 둘의 재료가 아니다(ADR 0020 §3·§5).
 */
public data class EnrichedEvent(
	public val observation: EventObservation,
	public val org: OrgAttribution,
)

/** 조직 보강을 마친 metric point 관측. [EnrichedEvent] 와 같은 규칙이다. */
public data class EnrichedMetricPoint(
	public val observation: MetricPointObservation,
	public val org: OrgAttribution,
)

/** push 하나의 보강 결과. 정규화 집계([stats])는 그대로 넘긴다. 입력과 출력의 행 수가 같다. */
public class EnrichedBatch(
	public val events: List<EnrichedEvent>,
	public val metricPoints: List<EnrichedMetricPoint>,
	public val stats: NormalizationStats,
)
