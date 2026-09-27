package com.team376.pulsemetry.telemetry.collector.masking

/**
 * 수집 단계가 적용하는 마스킹 정책의 버전. 아카이브 영수증과 분석 행의 `masking_version` 이 이 값이다
 * (ADR 0020 §6 · ADR 0021).
 *
 * 정책이란 **어느 시그널에 어떤 규칙을 거는가** 전체다 — 규칙 목록([MaskingRules])이나 그 순서, 적용
 * 대상 시그널(`Signal.masked`)이 바뀌면 올린다. 같은 원본이라도 정책이 다르면 아카이브에 남는 바이트가
 * 달라지므로, 재처리는 이 값으로 원래 정책을 구별한다.
 *
 * | 버전 | 내용 |
 * |---|---|
 * | `masking-v1` | logs·traces 에 `blocked_values` 열넷을 선언 순서로. metrics 는 마스킹하지 않는다(허브 계약 §5 M6) |
 * | `masking-v2` | v1 + metrics 의 resource·scope·data point 속성과 exemplar `filteredAttributes`(M6 해소) |
 */
public object MaskingPolicy {
	public const val VERSION: String = "masking-v2"
}
