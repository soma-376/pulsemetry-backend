package com.team376.pulsemetry.persistence.telemetry

import com.team376.pulsemetry.telemetry.adapter.observation.RowVersioning
import com.team376.pulsemetry.telemetry.enricher.observation.EnrichedEvent

/**
 * `telemetry_events` 에 쓰는 유일한 주체(ADR 0020 §1·§3). 쓰기 소유는 DDL 이 이 모듈 아래 있다는 사실이 근거다(ADR 0008
 * 규칙 1).
 *
 * ## 교체 저장
 *
 * 배치 하나를 INSERT 하나로 보낸다. 행은 모두 같은 [RowVersioning] 을 쓴다 — push 하나가 archive receipt 하나이고, live
 * 수신의 `ingest_seq` 가 그 receipt 의 시각이다. 같은 관측을 다시 적재하면 `ReplacingMergeTree(row_version)` 가 큰 버전을
 * 남긴다. 같은 배치를 재시도해도 `observation_id`·`row_version` 이 같아 한 행으로 수렴한다. 조회는 `FINAL` 이 필요하다.
 *
 * ## 실패
 *
 * 행 하나라도 컬럼 타입에 들어가지 않으면 **요청을 보내기 전에** [TelemetrySinkRejectedException] 이다 — 배치의 어느 행도
 * 쓰이지 않는다. ClickHouse 의 응답 분류는 [ClickHouseHttpClient] 그대로다(4xx 영구, 5xx·429·408 일시). 이벤트와 metric
 * point 두 테이블 사이의 원자성은 없다 — 재시도가 교체로 수렴한다.
 *
 * 빈이 아니다(ADR 0011). 클라이언트를 생성자로 받는다.
 */
public class TelemetryEventsSink(private val client: ClickHouseHttpClient) {

	/** 배치를 적재하고 적재한 행 수를 돌려준다. 빈 배치는 요청을 보내지 않는다. */
	public fun insert(events: List<EnrichedEvent>, versioning: RowVersioning): Int {
		if (events.isEmpty()) return 0
		val body = events.joinToString(separator = "\n", postfix = "\n") { TelemetryEventRow.toJson(it, versioning) }
		client.execute(QUERY, body.toByteArray(Charsets.UTF_8))
		return events.size
	}

	public companion object {
		public const val TABLE: String = "telemetry_events"

		private val QUERY: String = AnalysisInsert.query(TABLE, AnalysisColumns.EVENTS)
	}
}
