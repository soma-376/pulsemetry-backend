package com.team376.pulsemetry.telemetry.enricher

import com.team376.pulsemetry.telemetry.adapter.model.Normalized

/**
 * 보강 단계를 흐르는 컨테이너. 정규화 이벤트를 감싸고 파생·주석 필드만 여기에 쌓는다.
 *
 * **모듈 경계를 넘는 공개 API 다.** 적재 단계(`:libs:telemetry-persistence`)가 이 타입을
 * 그대로 받는다 — 단계 모듈 사이의 데이터 타입 간선을 인정한 ADR 0014 의 대상이다.
 *
 * [teamIdsAsOf] 만 whitelist 컬럼으로 승격되고 나머지 provider 산출물은 [annotations] 에
 * 남아 `enrichment_json` 으로만 적재된다(ADR 0017). 새 provider 의 산출물을
 * 컬럼으로 올리려면 그 ADR 을 개정해야 한다.
 */
public class Enriched(
	public val event: Normalized,

	/**
	 * 이벤트 발생 시각 기준 소속 팀. `org` provider 가 부수효과로 채운다.
	 *
	 * 한 구성원이 같은 시각에 여러 팀에 속할 수 있어 단수가 아니라 목록이다
	 * (`TeamMembership` KDoc · 허브 `contracts/data-model.md` D-3).
	 */
	public var teamIdsAsOf: List<String> = emptyList(),

	/** provider 이름 → 그 provider 의 산출물. 적재 시 `enrichment_json` 이 된다. */
	public val annotations: MutableMap<String, Any?> = LinkedHashMap(),
) {

	/**
	 * ClickHouse `enriched_events` 의 멱등 키.
	 *
	 * `record_id` 는 어댑터가 항상 스탬프하지만, **빈 키가 ReplacingMergeTree 의 전 행을
	 * 하나로 합치는 사고**를 막기 위해 `source_record_id` 로 방어한다. 둘 다 비면 빈 문자열이다 —
	 * 그 경우는 이식 원본에서도 방어의 마지막 칸이다.
	 */
	public val eventId: String
		get() {
			val envelope = event.envelope
			if (envelope.recordId.isNotEmpty()) return envelope.recordId
			return envelope.ingest.sourceRecordId?.takeIf { it.isNotEmpty() } ?: ""
		}

	/**
	 * 검증된 tenant. 수집 단계의 `IdentityStamp` 가 인증 결과의 tenant·installation 을 리소스
	 * 속성에 심고(ADR 0016) 어댑터가 그 값을 `Envelope.identity` 에 싣는다 — 클라이언트가 보낸
	 * 값은 그 자리에서 덮어써진다. 스탬프 없이 정규화된 이벤트(테스트·재처리)에서만 클라이언트 값이다.
	 */
	public val tenantId: String?
		get() = event.envelope.identity.tenantId

	public val signal: String
		get() = event.envelope.ingest.signal.wire

	public val product: String
		get() = event.envelope.client.product

	/**
	 * 이벤트 시각(epoch 초, 소수부 포함).
	 *
	 * 어댑터의 `Envelope.timestamp` 는 non-null 이고 파싱 실패 시 `0.0` 이다(어댑터가 고정한 현행 결함).
	 */
	public val timestamp: Double
		get() = event.envelope.timestamp
}
