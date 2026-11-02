package com.team376.pulsemetry.telemetry.model

/** 원본 시각 선택의 결과. */
public sealed interface SourceTimeSelection {
	public data class Selected(
		val sourceTime: EpochNanos,
		val origin: SourceTimeOrigin,
		/** 로그의 유효한 `observedTimeUnixNano`. 스팬·metric point 는 null. */
		val observedTime: EpochNanos?,
	) : SourceTimeSelection

	public data class Rejected(val reason: SourceTimeRejection) : SourceTimeSelection
}

/**
 * `source_time` 을 얻지 못한 사유. 선언 순서가 정보량 순서다 — 두 후보가 모두 실패하면 뒤의 것을 센다.
 */
public enum class SourceTimeRejection(override val wire: String) : WireValue {
	/** 값이 없거나 OTLP 의 미설정 값 0 이다. */
	UNSET("unset"),

	/** 부호 없는 64비트로는 읽혔지만 `DateTime64(9)` 로 표현할 수 없다(2^63 나노초 이상). */
	OUT_OF_RANGE("out_of_range"),

	/** OTLP/JSON 의 문자열이 부호 없는 10진 정수가 아니다. */
	UNPARSEABLE("unparseable"),
}
