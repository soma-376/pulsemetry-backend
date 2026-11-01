package com.team376.pulsemetry.telemetry.model

/** 제품·결과의 유한 집합만 계측한다. 수집 거부나 저장 완료를 뜻하지 않는다(ADR 0074). */
public enum class UsageContractOutcome(public val wire: String) {
	COMPATIBLE("compatible"),
	INCOMPLETE("incomplete"),
	INVALID("invalid"),
	UNVERIFIED("unverified"),
}

public data class UsageContractResult(public val product: Product, public val outcome: UsageContractOutcome)
