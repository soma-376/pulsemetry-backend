package com.team376.pulsemetry.domain.enrollment.seat

import com.team376.pulsemetry.domain.enrollment.seat.SeatControlRules.Action
import com.team376.pulsemetry.domain.enrollment.seat.SeatControlRules.Connection
import com.team376.pulsemetry.domain.enrollment.seat.SeatControlRules.Method
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SeatControlRulesTest {
    private val connection = Connection(UUID.randomUUID(), true, true, true)

    @Test fun `연결 오류보다 현재 상태가 먼저 회수 복원을 제한한다`() {
        for (state in SeatState.entries) {
            val release = SeatControlRules.choose(Action.RELEASE, state, connection.copy(planMatches = false), null, false)
            assertEquals(if (state == SeatState.ASSIGNED) "plan_mismatch" else "not_assigned", release.reason)
            val restore = SeatControlRules.choose(Action.RESTORE, state, null, null, true)
            assertEquals(when (state) { SeatState.RELEASED -> null; SeatState.PENDING_RELEASE -> "not_restorable"; else -> "seat_reassigned" }, restore.reason)
        }
    }

    @Test fun `벤더 제어는 플랜 계정 식별자 실행 가용성 순서로 검사한다`() {
        fun reason(facts: Connection, ref: String?, enabled: Boolean) = SeatControlRules.choose(Action.RELEASE, SeatState.ASSIGNED, facts, ref, enabled).reason
        assertEquals("plan_mismatch", reason(connection.copy(planMatches = false), null, false))
        assertEquals("vendor_account_unknown", reason(connection, null, false))
        assertEquals("connector_unavailable", reason(connection, "account", false))
        val result = SeatControlRules.choose(Action.RELEASE, SeatState.ASSIGNED, connection, "account", true)
        assertEquals(Method.VENDOR_CONTROL, result.method)
        assertEquals(connection.id, result.connectionId)
        assertNull(result.reason)
    }

    @Test fun `연결이 없거나 동작을 지원하지 않으면 관리자 확인이 필요하다`() {
        for (facts in listOf(null, connection.copy(supportsAction = false))) {
            val result = SeatControlRules.choose(Action.RELEASE, SeatState.ASSIGNED, facts, null, false)
            assertEquals(Method.ADMIN_ACTION, result.method)
            assertNull(result.connectionId)
        }
    }
}
