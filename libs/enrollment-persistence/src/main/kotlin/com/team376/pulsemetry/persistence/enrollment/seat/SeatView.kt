package com.team376.pulsemetry.persistence.enrollment.seat

/**
 * 좌석 한 자리의 응답 모양 (ADR 0048). 좌석 명령(enrollment-api)과 좌석 조회가 같이 쓴다.
 * `memberLink` 가 `admin` 인데 `memberId` 가 null 이면 관리자가 "잇지 않음"으로 정한 것이다. `vendorLastActivityAt` 이 null 이면 모름이지 미사용이 아니다.
 */
data class SeatView(
	val seatAssignmentId: String,
	val vendorId: String,
	val account: String,
	val accountKind: String,
	val state: String,
	val source: String,
	val memberId: String?,
	val memberLink: String?,
	val tierId: String?,
	val vendorTier: String?,
	val assignedAt: String,
	val releaseEffectiveOn: String?,
	val releasedAt: String?,
	val vendorLastActivityAt: String?,
	val note: String?,
	val version: Long,
	val updatedAt: String,
) {
	companion object {
		fun of(seat: SeatLedger.Seat) = SeatView(seat.id.toString(), seat.vendorId, seat.account, seat.accountKind.wire, seat.state.wire, seat.source.wire,
			seat.memberId?.toString(), seat.memberLink?.wire, seat.tierId, seat.vendorTier, seat.assignedAt.toString(), seat.releaseEffectiveOn?.toString(),
			seat.releasedAt?.toString(), seat.vendorLastActivityAt?.toString(), seat.note, seat.version, seat.updatedAt.toString())
	}
}
