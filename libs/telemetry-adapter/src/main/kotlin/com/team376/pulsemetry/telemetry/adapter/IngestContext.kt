package com.team376.pulsemetry.telemetry.adapter

import com.team376.pulsemetry.telemetry.adapter.model.SignalType

/**
 * 수집 시점 값들의 운반체. 어댑터 시그니처에 낱개로 흘리지 않고 하나로 묶어 넘긴다.
 *
 * [tenantId] 는 **클라이언트가 보낸 리소스 속성 `tenant.id` 에서 온다.** 이름과 달리
 * 인증에서 스탬프된 값이 아니다 — 구 파이프라인의 알려진 결함이고(그쪽
 * `data-gaps-and-schema-risks.md` 3.3) 이식은 동작 동일성이 기준이라 그대로 옮긴다.
 * 인증 기반 스탬핑은 조립 앱이 `:libs:security` 의 신원을 얹을 때 정리할 일이다.
 */
internal class IngestContext(
	val tenantId: String?,
	/** 원본 레코드에서 만든 추적용 해시. [SourceRecordId] 가 만든다. */
	val rawRecordId: String,
	val signal: SignalType,
)
