package com.team376.pulsemetry.telemetry.adapter.observation.codex

import com.team376.pulsemetry.telemetry.adapter.observation.Product
import com.team376.pulsemetry.telemetry.adapter.observation.profile.LogProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.MetricProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.ProductProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.SpanProfile

/**
 * Codex 이벤트 계약(ADR 0074). 등록된 CLI·APP_SERVER·Desktop은 같은 이름·분기·필드 검사를 사용한다.
 * producer 버전과 소스 위치는 진단 정보이며 매핑 선택 조건이 아니다.
 *
 * 로그는 [CodexLogs], 스팬 허용 목록은 [CodexSpans], 메트릭 family 는 [CodexMetrics] 가 정한다.
 *
 * 토큰 의미는 [CodexTokenSemantics]가 유효한 완료 사용량에만 적용한다(ADR 0061·0074).
 * provider는 다른 행에서 옮겨 채우지 않으며 진단 관측의 토큰을 대표 사용량에 더하지 않는다.
 */
public object CodexProfile : ProductProfile {

	override val product: Product = Product.CODEX
	override val mappingVersion: String = "codex-event-contract-v1"
	override val logs: LogProfile = CodexLogs
	override val spans: SpanProfile = CodexSpans
	override val metrics: MetricProfile = CodexMetrics
}
