package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.persistence.enrollment.seat.SeatState
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 기준 시각의 좌석 판정을 만든다 (ADR 0048 §7). 원장·관측·설치·완전성을 한 번씩 읽고 [SeatAssessment] 에 넘긴다.
 * [staleAfter] 는 연결의 마지막 성공 동기화가 낡았다고 보는 시간(필수 설정 `pulsemetry.dashboard.seats.stale-after`)이다.
 */
class SeatService(private val reader: SeatLedgerReader, private val policies: OrganizationPolicies, private val staleAfter: Duration) {

	/**
	 * [withReviews] 가 false 면 회수 검토에 필요한 관측(분석 원천·설치·완전성)을 읽지 않는다 — 좌석 상태·제품 가용성만 쓰는 목록이 쓴다.
	 * [roster] 는 그 조회가 쓰는 로스터(활성·정지 구성원)다.
	 */
	fun assess(tenant: UUID, asOf: Instant, roster: Set<UUID>, withReviews: Boolean = true): SeatAssessment {
		val ledger = reader.ledger(tenant, asOf)
		val idleDays = policies.of(tenant).reclaimIdleDays
		val review = withReviews && ledger.seats.any { it.state == SeatState.ASSIGNED && it.memberId != null }
		return SeatAssessment(
			ledger = ledger,
			staleAfter = staleAfter,
			idleDays = idleDays,
			roster = roster,
			lastUse = if (review) reader.lastUse(tenant, asOf, ledger.mapping) else emptyMap(),
			installations = if (review) reader.installations(tenant) else emptyMap(),
			windowComplete = review && reader.reviewWindow(tenant, asOf, idleDays).let { (dates, complete) -> complete.containsAll(dates) },
		)
	}

	fun roster(tenant: UUID, asOf: Instant) = reader.roster(tenant, asOf)
	fun teamNames(tenant: UUID) = reader.teamNames(tenant)
}
