package com.team376.pulsemetry.persistence.telemetry

import com.team376.pulsemetry.telemetry.adapter.NormalizedJson
import com.team376.pulsemetry.telemetry.enricher.Enriched

/**
 * 보강된 이벤트를 `enriched_events` 의 행으로 옮긴다.
 *
 * ## 화이트리스트가 계약이다
 *
 * [COLUMNS] 의 집합과 순서를 넘지 않는다. 새 컬럼을 늘리려면 ADR 0017 을 개정해야
 * 한다 — provider 산출물은 `enrichment_json` 으로만 적재하고, **승격 예외는 `team_ids_as_of`
 * 하나뿐**이라는 것이 그 결정이다. 특히 `member_id` 는 컬럼이 아니다. `raw_json` 안에는 있다.
 *
 * ## 강제되는 값 변환 — 전부 현행 동작이다
 *
 * - `ts` 는 컬럼이 `DateTime('UTC')` 라 **초로 절사**된다. `…000.5` 는 `…000` 이 된다
 * - 신원·분류 컬럼은 non-nullable 이라 없는 값이 **빈 문자열**이 된다. `null` 과 구별되지 않는다
 * - `raw_json` 은 어댑터의 [NormalizedJson] 이 만든다. golden fixture 의 `event` 가 그 값이다
 * - `enrichment_json` 은 **키 정렬** 표기다. 행 자체는 삽입 순서다 — [TelemetryJson] 참고
 */
public object EnrichedEventRow {

	/**
	 * 적재 컬럼과 그 순서. DDL 의 컬럼 순서와 같다.
	 *
	 * JSONEachRow 는 키 이름으로 매칭하므로 순서가 적재를 좌우하지는 않지만, 행 JSON 의
	 * 바이트가 이 순서로 정해지고 리뷰가 DDL 과 나란히 읽힌다.
	 */
	public val COLUMNS: List<String> = listOf(
		"event_id",
		"ts",
		"tenant_id",
		"installation_id",
		"signal",
		"product",
		"team_ids_as_of",
		"raw_json",
		"enrichment_json",
	)

	/** 행 하나를 만든다. 키 순서는 [COLUMNS] 와 같다. */
	public fun of(item: Enriched): Map<String, Any?> {
		val identity = item.event.envelope.identity
		return linkedMapOf(
			"event_id" to item.eventId,
			// 초로 절사한다. 반올림이 아니다 — 이식 원본의 int() 와 같다.
			"ts" to item.timestamp.toLong(),
			"tenant_id" to (item.tenantId ?: ""),
			// 프록시가 검증한 조인 키. 봉투에서 직접 읽는다 — Enriched 에 접근자를 두지 않는다.
			"installation_id" to (identity.installationId ?: ""),
			"signal" to item.signal,
			"product" to item.product,
			"team_ids_as_of" to item.teamIdsAsOf,
			"raw_json" to NormalizedJson.toJson(item.event),
			"enrichment_json" to TelemetryJson.sorted(item.annotations),
		)
	}

	/** 행 하나를 JSONEachRow 의 한 줄로 적는다. */
	public fun toJson(item: Enriched): String = TelemetryJson.compact(of(item))
}
