package com.team376.pulsemetry.persistence.enrollment.seat

import com.team376.pulsemetry.persistence.enrollment.seat.SeatControl.Action.RELEASE
import com.team376.pulsemetry.persistence.enrollment.seat.SeatControl.Action.RESTORE
import com.team376.pulsemetry.persistence.enrollment.seat.SeatControl.Method.ADMIN_ACTION
import com.team376.pulsemetry.persistence.enrollment.seat.SeatControl.Method.VENDOR_CONTROL
import com.team376.pulsemetry.persistence.enrollment.seat.SeatState.ASSIGNED
import com.team376.pulsemetry.persistence.enrollment.seat.SeatState.PENDING_ASSIGNMENT
import com.team376.pulsemetry.persistence.enrollment.seat.SeatState.PENDING_RELEASE
import com.team376.pulsemetry.persistence.enrollment.seat.SeatState.RELEASED
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * ADR 0049 §2 의 실행 방식 표. 기대값은 ADR 의 표와 벤더 근거 문서의 "결론 — 구현 방식"(해제는 커넥터 넷, 복원은 Copilot·Gemini 만 커넥터)에서 쓴다.
 */
class SeatControlRulesTest {

	private fun connection(connector: String) = ConnectionRecord(UUID.randomUUID(), UUID.randomUUID(), "v", connector, emptyMap(), Instant.EPOCH,
		ConnectionCheck.VERIFIED, null, null, null, null, 1, Instant.EPOCH, Instant.EPOCH)

	private fun choose(action: SeatControl.Action, state: SeatState, kind: String, plan: String?, connector: String? = null, ref: String? = "ref",
		vendorControl: Boolean = true): Pair<SeatControl.Method?, String?> =
		SeatControl.choose(action, state, kind, plan, connector?.let(::connection), ref, vendorControl).let { it.method to it.reason }

	@Test
	fun `해제 — 활성 연결과 커넥터가 있으면 벤더 제어, 연결이 없거나 커넥터가 없는 플랜이면 관리자 조치다`() {
		assertThat(choose(RELEASE, ASSIGNED, "copilot", "copilot_business", "copilot")).isEqualTo(VENDOR_CONTROL to null)
		assertThat(choose(RELEASE, ASSIGNED, "gemini", "gemini_standard", "gemini")).isEqualTo(VENDOR_CONTROL to null)
		assertThat(choose(RELEASE, ASSIGNED, "cursor", "cursor_enterprise", "cursor_enterprise")).isEqualTo(VENDOR_CONTROL to null)
		assertThat(choose(RELEASE, ASSIGNED, "claude_team", "enterprise", "claude_enterprise")).isEqualTo(VENDOR_CONTROL to null)
		// 연결 전(커넥터가 있는 플랜의 임시 기록)·커넥터가 없는 플랜·계약 없음은 관리자가 벤더 콘솔에서 한다.
		assertThat(choose(RELEASE, ASSIGNED, "copilot", "copilot_business")).isEqualTo(ADMIN_ACTION to null)
		assertThat(choose(RELEASE, ASSIGNED, "claude_team", "team")).isEqualTo(ADMIN_ACTION to null)
		assertThat(choose(RELEASE, ASSIGNED, "openai_biz", null)).isEqualTo(ADMIN_ACTION to null)
	}

	@Test
	fun `해제 — 배정 좌석만, 연결의 커넥터가 계약 플랜과 다르거나 구성원 ID 가 없거나 이 배포에 커넥터가 없으면 거절한다`() {
		listOf(PENDING_ASSIGNMENT, PENDING_RELEASE, RELEASED).forEach { state ->
			assertThat(choose(RELEASE, state, "claude_team", "team")).isEqualTo(null to "not_assigned")
		}
		assertThat(choose(RELEASE, ASSIGNED, "claude_team", "team", "claude_enterprise")).isEqualTo(null to "plan_mismatch")
		// 플랜에 커넥터가 있어도 연결의 커넥터가 그것이 아니면 부르지 않는다.
		assertThat(choose(RELEASE, ASSIGNED, "copilot", "copilot_business", "gemini")).isEqualTo(null to "plan_mismatch")
		assertThat(choose(RELEASE, ASSIGNED, "claude_team", "enterprise", "claude_enterprise", ref = null)).isEqualTo(null to "vendor_account_unknown")
		assertThat(choose(RELEASE, ASSIGNED, "cursor", "cursor_enterprise", "cursor_enterprise", ref = null)).describedAs("Cursor 는 이메일로도 부른다")
			.isEqualTo(VENDOR_CONTROL to null)
		assertThat(choose(RELEASE, ASSIGNED, "copilot", "copilot_business", "copilot", vendorControl = false)).isEqualTo(null to "connector_unavailable")
	}

	@Test
	fun `복원 — 커넥터가 복원을 구현했으면 벤더 제어, 아니면 관리자 조치이고 해제 예정 좌석은 벤더 제어로만 되살린다`() {
		assertThat(choose(RESTORE, RELEASED, "copilot", "copilot_business", "copilot")).isEqualTo(VENDOR_CONTROL to null)
		assertThat(choose(RESTORE, PENDING_RELEASE, "copilot", "copilot_business", "copilot")).isEqualTo(VENDOR_CONTROL to null)
		assertThat(choose(RESTORE, RELEASED, "gemini", "gemini_enterprise", "gemini")).isEqualTo(VENDOR_CONTROL to null)
		// Claude 재초대(역할을 정해야 한다)와 Cursor(복원 API 없음)는 관리자 조치다.
		assertThat(choose(RESTORE, RELEASED, "claude_team", "enterprise", "claude_enterprise")).isEqualTo(ADMIN_ACTION to null)
		assertThat(choose(RESTORE, RELEASED, "cursor", "cursor_enterprise", "cursor_enterprise")).isEqualTo(ADMIN_ACTION to null)
		assertThat(choose(RESTORE, RELEASED, "openai_biz", "business")).isEqualTo(ADMIN_ACTION to null)
		assertThat(choose(RESTORE, PENDING_RELEASE, "copilot", "copilot_business")).isEqualTo(null to "not_restorable")
		listOf(ASSIGNED, PENDING_ASSIGNMENT).forEach { state ->
			assertThat(choose(RESTORE, state, "openai_biz", "business")).isEqualTo(null to "seat_reassigned")
		}
	}
}
