package com.team376.pulsemetry.domain.enrollment.seat

import java.util.UUID

/** ADR 0049의 실행 방식 판정. 연결 저장소·커넥터 실행·HTTP를 알지 않는다. */
object SeatControlRules {
    enum class Action(val wire: String) {
        RELEASE("release"), RESTORE("restore");
        companion object { fun of(wire: String): Action = entries.first { it.wire == wire } }
    }

    enum class Method(val wire: String) {
        VENDOR_CONTROL("vendor_control"), ADMIN_ACTION("admin_action");
        companion object { fun of(wire: String): Method = entries.first { it.wire == wire } }
    }

    data class Choice(val method: Method?, val connectionId: UUID?, val reason: String?) {
        companion object { fun rejected(reason: String) = Choice(null, null, reason) }
    }

    /** 저장된 연결과 공개 커넥터 설명을 읽은 사실. 요청된 동작의 지원 여부만 전달한다. */
    data class Connection(val id: UUID, val planMatches: Boolean, val supportsAction: Boolean, val requiresAccountRef: Boolean)

    fun controllable(action: Action, state: SeatState): Boolean = when (action) {
        Action.RELEASE -> state == SeatState.ASSIGNED
        Action.RESTORE -> state == SeatState.RELEASED || state == SeatState.PENDING_RELEASE
    }

    fun choose(action: Action, state: SeatState, connection: Connection?, vendorAccountRef: String?, vendorControl: Boolean): Choice {
        if (!controllable(action, state)) return Choice.rejected(if (action == Action.RELEASE) "not_assigned" else "seat_reassigned")
        if (connection != null) {
            if (!connection.planMatches) return Choice.rejected("plan_mismatch")
            if (connection.supportsAction) {
                if (connection.requiresAccountRef && vendorAccountRef == null) return Choice.rejected("vendor_account_unknown")
                if (!vendorControl) return Choice.rejected("connector_unavailable")
                return Choice(Method.VENDOR_CONTROL, connection.id, null)
            }
        }
        if (action == Action.RESTORE && state == SeatState.PENDING_RELEASE) return Choice.rejected("not_restorable")
        return Choice(Method.ADMIN_ACTION, null, null)
    }
}
