package com.team376.pulsemetry.domain.enrollment.installation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LossCounterTest {
    @Test fun `같은 실행 재보고와 새 실행 증가분의 합계는 10이다`() {
        fun total(values: List<Long>): Long {
            var high = 0L
            return values.sumOf { value -> LossCounter.observe(high, value).also { high = it.highWater }.delta }
        }
        assertThat(total(listOf(5, 5, 8)) + total(listOf(0, 2))).isEqualTo(10)
    }

    @Test fun `감소와 복구는 최고값을 낮추거나 손실을 더하지 않는다`() {
        val decreased = LossCounter.observe(8, 5)
        assertThat(decreased).isEqualTo(LossCounter.Change(0, 8, true))
        assertThat(LossCounter.observe(decreased.highWater, 8)).isEqualTo(LossCounter.Change(0, 8, false))
    }

    @Test fun `최대 Long도 부동소수점 변환이나 덧셈 오버플로 없이 계산한다`() {
        assertThat(LossCounter.observe(Long.MAX_VALUE - 1, Long.MAX_VALUE).delta).isEqualTo(1)
    }
}
