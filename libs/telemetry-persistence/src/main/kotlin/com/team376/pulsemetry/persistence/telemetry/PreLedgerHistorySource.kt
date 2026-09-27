package com.team376.pulsemetry.persistence.telemetry

/**
 * 요약 도입 전 분석 이력의 원천 — 구 `enriched_events` 에 행이 있는 서로 다른 tenant(ADR 0021 §2 백필). 백필은
 * `:libs:telemetry-ops-persistence` 의 `TenantSummaryBackfill` 이 하고, 조립 앱이 이 읽기를 그쪽에 넘긴다.
 *
 * 값을 거르지 않는다 — 빈 문자열만 뺀다. UUID 가 아닌 값을 어떻게 다룰지는 백필이 정한다. `enriched_events` 는 새 행을
 * 받지 않게 된 뒤에도 남으므로(ADR 0020 §10) 전환 뒤에 돌려도 같은 답이다.
 */
public class PreLedgerHistorySource(private val client: ClickHouseHttpClient) {

	/** 원천 테이블 이름. 백필 완료 기록의 `source` 에 남긴다. */
	public val source: String = TABLE

	public fun tenantIds(): List<String> =
		client.execute("SELECT DISTINCT tenant_id FROM $TABLE WHERE tenant_id != '' ORDER BY tenant_id FORMAT TSVRaw")
			.lines()
			.filter { it.isNotEmpty() }

	private companion object {
		const val TABLE: String = "enriched_events"
	}
}
