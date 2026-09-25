package com.team376.pulsemetry.telemetry.adapter.observation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** 금액 컬럼 `Decimal(38, 12)` 의 경계 — 소수 12자리, 정수부 26자리(ADR 0020 §1). 뒤따르는 0 은 자릿수가 아니다. */
class MoneyTest {

	@Test
	@DisplayName("경계 안의 값은 들어간다 — 뒤따르는 0 은 세지 않는다")
	fun fitsWithinBounds() {
		assertThat(Money.fits(BigDecimal("0.000000000001"))).isTrue()
		assertThat(Money.fits(BigDecimal("99999999999999999999999999.999999999999"))).isTrue()
		assertThat(Money.fits(BigDecimal("0.02000000000000000"))).isTrue()
		assertThat(Money.fits(BigDecimal("1E+25"))).isTrue()
		assertThat(Money.fits(BigDecimal.ZERO)).isTrue()
	}

	@Test
	@DisplayName("소수 13자리 이상이나 정수부 27자리 이상은 들어가지 않는다 — 절삭하지 않는다")
	fun rejectsOutOfBounds() {
		assertThat(Money.fits(BigDecimal("0.0000000000001"))).isFalse()
		assertThat(Money.fits(BigDecimal("100000000000000000000000000"))).isFalse()
		assertThat(Money.fitsIntegerPart(BigDecimal("0.0000000000001"))).isTrue()
		assertThat(Money.fitsIntegerPart(BigDecimal("1E+26"))).isFalse()
	}
}
