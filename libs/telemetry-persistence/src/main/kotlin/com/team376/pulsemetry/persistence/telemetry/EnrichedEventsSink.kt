package com.team376.pulsemetry.persistence.telemetry

import com.team376.pulsemetry.telemetry.enricher.Enriched

/**
 * `enriched_events` 에 쓰는 **유일한 주체**다.
 *
 * 허브 `architecture/overview.md` 3절이 Signal Database 의 쓰기를 Enricher 노드에 주었고,
 * 이 저장소는 그 노드를 결합과 적재 두 모듈로 나눠 구현한다 — **쓰기는 이 모듈만 한다**(I-4).
 * 보강 모듈도 조립 앱도 ClickHouse 에 쓰지 않는다.
 *
 * ## 멱등
 *
 * 배치 하나를 POST 하나로 보낸다. 중복 제거 키는 `event_id`(= `envelope.record_id`)이고 엔진이
 * `ReplacingMergeTree` 라 **같은 배치를 다시 적재해도 결과가 같다.** 다만 병합 전에는 행이
 * 둘 다 남으므로 **조회에 `FINAL` 이 필요하다.**
 *
 * ## 여기 없는 것
 *
 * 재시도도, 배치 누적도, 레코드 단위 격리도 없다. 실패는 [TelemetrySinkUnavailableException]
 * 으로 나가고 그 처리는 조립 앱의 몫이다. 레코드 하나가 나빠도 **배치 전체가 실패한다** —
 * 이식 원본의 알려진 공백이고 DLQ 는 아직 없다.
 *
 * ## 조립
 *
 * 빈이 아니다(ADR 0011). 클라이언트를 생성자로 받는다.
 */
public class EnrichedEventsSink(
	private val client: ClickHouseHttpClient,
) {

	/**
	 * 배치를 적재하고 적재한 행 수를 돌려준다. 빈 배치는 요청을 보내지 않는다.
	 */
	public fun insert(items: List<Enriched>): Int {
		if (items.isEmpty()) return 0

		val body = items.joinToString(separator = "\n", postfix = "\n") { EnrichedEventRow.toJson(it) }
		client.execute("INSERT INTO $TABLE FORMAT JSONEachRow", body.toByteArray(Charsets.UTF_8))
		return items.size
	}

	/** 중복 제거 후 행 수. `FINAL` 이 있어야 재적재분이 합쳐진 값이 나온다. */
	public fun countDistinct(): Int = client.execute("SELECT count() FROM $TABLE FINAL").trim().toInt()

	public companion object {
		public const val TABLE: String = "enriched_events"
	}
}
