package com.team376.pulsemetry.persistence.telemetry

import com.team376.pulsemetry.telemetry.adapter.observation.RowVersioning
import com.team376.pulsemetry.telemetry.enricher.observation.EnrichedMetricPoint

/**
 * `telemetry_metric_points` 에 쓰는 유일한 주체(ADR 0020 §1·§3). 교체 저장·실패·원자성 규칙은 [TelemetryEventsSink] 와 같다.
 */
public class TelemetryMetricPointsSink(private val client: ClickHouseHttpClient) {

	/** 배치를 적재하고 적재한 행 수를 돌려준다. 빈 배치는 요청을 보내지 않는다. */
	public fun insert(points: List<EnrichedMetricPoint>, versioning: RowVersioning): Int {
		if (points.isEmpty()) return 0
		val body = points.joinToString(separator = "\n", postfix = "\n") { TelemetryMetricPointRow.toJson(it, versioning) }
		client.execute(QUERY, body.toByteArray(Charsets.UTF_8))
		return points.size
	}

	public companion object {
		public const val TABLE: String = "telemetry_metric_points"

		private val QUERY: String = AnalysisInsert.query(TABLE, AnalysisColumns.METRIC_POINTS)
	}
}
