package com.team376.pulsemetry.domain.enrollment.installation

/** 실행별 최고 누계는 낮추지 않는다. 감소 후 같은 누계의 재보고도 손실을 다시 만들지 않는다. */
object LossCounter {
    data class Change(val delta: Long, val highWater: Long, val decreased: Boolean)

    fun observe(highWater: Long, reported: Long): Change {
        require(highWater >= 0 && reported >= 0)
        return Change(if (reported > highWater) reported - highWater else 0, maxOf(highWater, reported), reported < highWater)
    }
}
