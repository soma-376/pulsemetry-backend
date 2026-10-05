package com.team376.pulsemetry.persistence.enrollment.seat

import java.time.Duration
import java.time.Instant

/** 좌석 상태 (ADR 0048 §2). 해제만 좌석을 차지하지 않는다. */
enum class SeatState(val wire: String) {
	ASSIGNED("assigned"), PENDING_ASSIGNMENT("pending_assignment"), PENDING_RELEASE("pending_release"), RELEASED("released");

	val holds: Boolean get() = this != RELEASED

	companion object {
		fun of(wire: String): SeatState = entries.first { it.wire == wire }
	}
}

/** 좌석의 상태를 정한 원천 (ADR 0048 §3). */
enum class SeatSource(val wire: String) {
	CONNECTOR("connector"), MANUAL("manual"), CSV("csv"), VENDOR_CONTROL("vendor_control"), ADMIN_ACTION("admin_action");

	companion object {
		fun of(wire: String): SeatSource = entries.first { it.wire == wire }
	}
}

/** 구성원 연결의 근거 (ADR 0048 §4). */
enum class MemberLink(val wire: String) {
	EMAIL_MATCH("email_match"), ADMIN("admin");

	companion object {
		fun of(wire: String?): MemberLink? = wire?.let { value -> entries.first { it.wire == value } }
	}
}

/** ADR 0048 §2의 전이 표. `from` 이 null 이면 새 좌석이다. 같은 상태로의 "전이"는 상태 변화가 아니다. */
object SeatTransitions {
	private val CONTROL = setOf(
		SeatState.ASSIGNED to SeatState.PENDING_RELEASE, SeatState.ASSIGNED to SeatState.RELEASED,
		SeatState.PENDING_RELEASE to SeatState.ASSIGNED, SeatState.PENDING_RELEASE to SeatState.RELEASED,
		SeatState.RELEASED to SeatState.PENDING_ASSIGNMENT, SeatState.RELEASED to SeatState.ASSIGNED,
		SeatState.PENDING_ASSIGNMENT to SeatState.ASSIGNED,
	)
	private val ADMIN_ACTION = setOf(
		SeatState.ASSIGNED to SeatState.RELEASED, SeatState.PENDING_RELEASE to SeatState.RELEASED, SeatState.RELEASED to SeatState.ASSIGNED,
	)

	fun allowed(source: SeatSource, from: SeatState?, to: SeatState): Boolean = when (source) {
		// 벤더가 보고한 보유 상태는 무엇에서든. 목록에 없으면 보유 좌석만 해제된다.
		SeatSource.CONNECTOR -> to.holds || from?.holds == true
		SeatSource.MANUAL, SeatSource.CSV ->
			((from == null || from == SeatState.RELEASED) && to == SeatState.ASSIGNED) || (from == SeatState.ASSIGNED && to == SeatState.RELEASED)
		SeatSource.VENDOR_CONTROL -> from != null && (from to to) in CONTROL
		SeatSource.ADMIN_ACTION -> from != null && (from to to) in ADMIN_ACTION
	}
}

/** 등록 제품의 좌석 권위 (ADR 0048 §3의 우선순위 표). */
data class SeatAuthority(val authority: Authority, val provisional: Boolean) {
	enum class Authority(val wire: String) { CONNECTOR("connector"), MANUAL("manual") }

	/** 수동·CSV 로 배정·해제를 기록할 수 있는가. 보정(구성원 연결·계약 등급·메모)은 권위와 무관하게 된다. */
	val allowsManualAssignment: Boolean get() = authority == Authority.MANUAL

	companion object {
		/**
		 * @param connectorPlan 그 등록 제품·계약 플랜에 커넥터 설명이 있는가.
		 * @param activeConnection 활성 연결이 있는가.
		 */
		fun of(connectorPlan: Boolean, activeConnection: Boolean): SeatAuthority = when {
			activeConnection -> SeatAuthority(Authority.CONNECTOR, provisional = false)
			connectorPlan -> SeatAuthority(Authority.MANUAL, provisional = true)
			else -> SeatAuthority(Authority.MANUAL, provisional = false)
		}
	}
}

/** 연결의 동기화 상태 (ADR 0048 §7). 낡음 판정의 기준 시간은 조회 앱의 필수 설정이다. */
enum class SyncStatus(val wire: String) {
	PENDING("pending"), SUCCEEDED("succeeded"), FAILING("failing");

	companion object {
		/** 마지막 시도가 실패면 failing, 성공한 적이 없으면 pending, 아니면 succeeded. */
		fun of(lastSucceededAt: Instant?, lastFailedAt: Instant?): SyncStatus = when {
			lastFailedAt != null && (lastSucceededAt == null || lastFailedAt > lastSucceededAt) -> FAILING
			lastSucceededAt == null -> PENDING
			else -> SUCCEEDED
		}

		/** 원장 값이 낡았는가 — 실패 중이거나 마지막 성공이 [staleAfter] 보다 오래됐다. 성공한 적 없으면 값 자체가 없다(낡음이 아니다). */
		fun stale(lastSucceededAt: Instant?, lastFailedAt: Instant?, now: Instant, staleAfter: Duration): Boolean =
			lastSucceededAt != null && (of(lastSucceededAt, lastFailedAt) == FAILING || lastSucceededAt.plus(staleAfter) < now)
	}
}
