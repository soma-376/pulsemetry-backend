package com.team376.pulsemetry.telemetry.adapter

import com.team376.pulsemetry.telemetry.adapter.model.SignalType

/**
 * 수집 시점 값들의 운반체. 어댑터 시그니처에 낱개로 흘리지 않고 하나로 묶어 넘긴다.
 *
 * [tenantId] 는 리소스 속성 `tenant.id` 에서 읽는다. 그 값은 수집 단계의 `IdentityStamp` 가
 * 마스킹 뒤·아카이브 앞에서 검증된 tenant·installation 을 리소스 속성에 심은 것이다
 * (ADR 0016). 이 모듈은 그 값을 읽기만 한다.
 */
internal class IngestContext(
	val tenantId: String?,
	/** 원본 레코드에서 만든 추적용 해시. [SourceRecordId] 가 만든다. */
	val rawRecordId: String,
	val signal: SignalType,
)
