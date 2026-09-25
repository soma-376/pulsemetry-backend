package com.team376.pulsemetry.telemetry.adapter.observation

import java.math.BigDecimal

/**
 * 분석 테이블의 금액 컬럼 타입 `Decimal(38, 12)`(ADR 0020 §1). 금액은 절삭하지 않으므로, 반올림 없이 들어가지 않는 값은
 * 컬럼에 올리지 않고 검증에서 거부한다 — 측정 필드면 null + `invalid_measurement`, 적재 직전이면 적재 거부다.
 */
public object Money {

	public const val PRECISION: Int = 38

	public const val SCALE: Int = 12

	/** 값이 반올림 없이 `Decimal(38, 12)` 에 들어가는가 — 의미 있는 소수 자릿수 12 이하, 정수부 26자리 이하. */
	public fun fits(value: BigDecimal): Boolean = value.stripTrailingZeros().scale() <= SCALE && fitsIntegerPart(value)

	/** 정수부가 26자리 이하인가. 소수부를 컬럼에 맞출 수 있어도 정수부가 넘치면 어떤 규칙으로도 들어가지 않는다. */
	public fun fitsIntegerPart(value: BigDecimal): Boolean {
		val stripped = value.stripTrailingZeros()
		return stripped.precision() - stripped.scale() <= PRECISION - SCALE
	}
}
