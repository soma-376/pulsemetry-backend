package com.team376.pulsemetry.dashboard.analytics

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * 화면 요청서의 `Money` — USD 10진 문자열. 소수점 아래를 **최소 6자리**로 적는다(요청서 예시 `"1234.560000"`)
 * 그보다 정밀한 값은 자르지 않는다 — 합계 검증이 서버 정밀도(`Decimal(38, 12)`)로 성립해야 한다.
 */
object Money {

	private const val MIN_SCALE = 6
	private val MILLION = BigDecimal(1_000_000)

	fun format(value: BigDecimal): String {
		val stripped = value.stripTrailingZeros()
		return stripped.setScale(maxOf(MIN_SCALE, stripped.scale())).toPlainString()
	}

	/** `cost / tokens × 10⁶`. 어느 쪽이든 없거나 토큰이 0 이면 없다 — 0 으로 나누지 않는다. */
	fun perMillion(cost: BigDecimal?, tokens: Long?): BigDecimal? {
		if (cost == null || tokens == null || tokens == 0L) return null
		return cost.multiply(MILLION).divide(BigDecimal(tokens), 12, RoundingMode.HALF_EVEN)
	}

	/** `part / whole`. 어느 쪽이든 없거나 전체가 0 이면 없다. */
	fun share(part: BigDecimal?, whole: BigDecimal?): Double? {
		if (part == null || whole == null || whole.signum() == 0) return null
		return part.divide(whole, MathContext.DECIMAL64).toDouble()
	}
}
