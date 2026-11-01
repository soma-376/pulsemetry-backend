package com.team376.pulsemetry.persistence.enrollment.seat

import com.team376.pulsemetry.domain.enrollment.seat.SeatControlRules

import com.team376.pulsemetry.connector.vendor.Capability
import com.team376.pulsemetry.connector.vendor.ConnectorDescriptors
import com.team376.pulsemetry.domain.enrollment.seat.SeatControlRules.Action
import com.team376.pulsemetry.domain.enrollment.seat.SeatState

/** 읽기 모델을 순수 규칙의 입력으로 바꾼다. 조회 앱은 명령 실행기 SeatControl에 의존하지 않는다. */
object SeatControlChoice {
    fun choose(action: Action, state: SeatState, kind: String, plan: String?, connection: ConnectionRecord?,
        vendorAccountRef: String?, vendorControl: Boolean): SeatControlRules.Choice {
        val facts = connection?.let {
            val descriptor = ConnectorDescriptors.forPlan(kind, plan)
            val capability = when (action) { Action.RELEASE -> Capability.SEAT_RELEASE; Action.RESTORE -> Capability.SEAT_RESTORE }
            SeatControlRules.Connection(it.id, descriptor?.id == it.connector,
                descriptor?.capabilities?.contains(capability) == true, descriptor?.accountRefRequired?.contains(capability) == true)
        }
        return SeatControlRules.choose(action, state, facts, vendorAccountRef, vendorControl)
    }
}
