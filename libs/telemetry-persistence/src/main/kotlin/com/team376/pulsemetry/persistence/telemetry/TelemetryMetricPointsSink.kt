package com.team376.pulsemetry.persistence.telemetry

import com.team376.pulsemetry.telemetry.model.RowVersioning
import com.team376.pulsemetry.telemetry.model.EnrichedMetricPoint

/**
 * `telemetry_metric_points` 에 쓰는 유일한 주체(ADR 0020 §1·§3). 교체 저장·삭제 경계·실패·원자성 규칙은 [TelemetryEventsSink] 와 같다.
 */
public class TelemetryMetricPointsSink(private val client: ClickHouseHttpClient) : AnalysisLinesSink {

	override val table: String get() = TABLE

	/** 경계 안의 행을 적재하고 그 수를 돌려준다. 실을 행이 없으면 요청을 보내지 않는다. */
	public fun insert(points: List<EnrichedMetricPoint>, versioning: RowVersioning, boundary: AnalysisWriteBoundary): Int {
		val lines = encode(points, versioning, boundary)
		send(lines, boundary)
		return lines.size
	}

	/** 경계 안의 행을 JSONEachRow 줄로 바꾼다. 요청은 보내지 않는다. 거부 규칙은 [TelemetryEventsSink.encode] 와 같다. */
	public fun encode(points: List<EnrichedMetricPoint>, versioning: RowVersioning, boundary: AnalysisWriteBoundary): List<String> =
		boundary.admitted(points) { it.observation.envelope }.map { TelemetryMetricPointRow.toJson(it, versioning) }

	override fun send(lines: List<String>, boundary: AnalysisWriteBoundary) {
		if (lines.isEmpty()) return
		client.rowsSubmitted(TABLE, lines.size)
		AnalysisInsert.send(client, QUERY, AnalysisLinesSink.body(lines), boundary, TABLE)
	}

	public companion object {
		public const val TABLE: String = "telemetry_metric_points"

		private val QUERY: String = AnalysisInsert.fenced(TABLE, AnalysisColumns.METRIC_POINTS)
	}
}
