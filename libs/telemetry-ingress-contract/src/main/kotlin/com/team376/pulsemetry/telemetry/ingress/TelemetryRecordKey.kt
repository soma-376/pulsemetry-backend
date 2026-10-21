package com.team376.pulsemetry.telemetry.ingress

import java.util.UUID

/**
 * ingress record 의 key — `tenant_id:installation_id`(ADR 0066 Decision 4). 같은 설치의 record 가 같은 partition 에
 * 모여 순서가 유지된다. key 는 신원이 아니다 — Consumer 는 key 를 읽지 않고 envelope 와 RDB 로 판정한다.
 */
public object TelemetryRecordKey {

	public fun of(tenantId: UUID, installationId: UUID): String = "$tenantId:$installationId"
}
