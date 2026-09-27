package com.team376.pulsemetry.telemetry.adapter.observation

import java.time.Instant

/**
 * UTC 기준 epoch 나노초. 분석 테이블의 `DateTime64(9, 'UTC')` 값이다.
 *
 * **Double 초를 거치지 않는다**(ADR 0020 §2). OTLP 의 시각은 부호 없는 64비트 나노초인데, 이 타입은
 * 부호 있는 `Long` 이라 2262-04-11 을 넘는 값을 담지 못한다 — `DateTime64(9)` 의 표현 범위도 거기서
 * 끝나므로 같은 한계다. 범위를 넘는 원본은 이 타입이 되기 전에 거부된다.
 */
@JvmInline
public value class EpochNanos(public val value: Long) : Comparable<EpochNanos> {

	override fun compareTo(other: EpochNanos): Int = value.compareTo(other.value)

	public fun toInstant(): Instant = Instant.ofEpochSecond(Math.floorDiv(value, NANOS_PER_SECOND), Math.floorMod(value, NANOS_PER_SECOND))

	public companion object {
		private const val NANOS_PER_SECOND: Long = 1_000_000_000L

		/** 나노초까지 보존한다. `Long` 범위를 넘는 시각은 [ArithmeticException] 이다. */
		public fun of(instant: Instant): EpochNanos =
			EpochNanos(Math.addExact(Math.multiplyExact(instant.epochSecond, NANOS_PER_SECOND), instant.nano.toLong()))
	}
}
