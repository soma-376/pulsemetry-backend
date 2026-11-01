package com.team376.pulsemetry.telemetry.model

/** 64자리 소문자 hex — `FixedString(64)` 컬럼의 값(ADR 0020 §1). 길이는 바이너리 digest 가 아니라 ASCII hex 다. */
public object Hex64 {
	private val PATTERN = Regex("^[0-9a-f]{64}$")

	public fun isValid(value: String): Boolean = PATTERN.matches(value)

	public fun require(column: String, value: String) {
		require(isValid(value)) { "$column 은 64자리 소문자 hex 다: $value" }
	}
}
