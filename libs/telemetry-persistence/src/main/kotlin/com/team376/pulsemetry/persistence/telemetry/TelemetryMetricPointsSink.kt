package com.team376.pulsemetry.persistence.telemetry

import com.team376.pulsemetry.telemetry.adapter.observation.RowVersioning
import com.team376.pulsemetry.telemetry.enricher.observation.EnrichedMetricPoint

/**
 * `telemetry_metric_points` 에 쓰는 유일한 주체(ADR 0020 §1·§3). 교체 저장·삭제 경계·실패·원자성 규칙은 [TelemetryEventsSink] 와 같다.
 */
public class TelemetryMetricPointsSink(private val client: ClickHouseHttpClient) {

	/** 경계 안의 행을 적재하고 그 수를 돌려준다. 실을 행이 없으면 요청을 보내지 않는다. */
	public fun insert(points: List<EnrichedMetricPoint>, versioning: RowVersioning, boundary: AnalysisWriteBoundary): Int {
		val admitted = boundary.admitted(points) { it.observation.envelope }
		if (admitted.isEmpty()) return 0
		val body = admitted.joinToString(separator = "\n", postfix = "\n") { TelemetryMetricPointRow.toJson(it, versioning) }
		AnalysisInsert.send(client, QUERY, body, boundary)
		return admitted.size
	}

	public companion object {
		public const val TABLE: String = "telemetry_metric_points"

		private val QUERY: String = AnalysisInsert.fenced(TABLE, AnalysisColumns.METRIC_POINTS)
	}
}
