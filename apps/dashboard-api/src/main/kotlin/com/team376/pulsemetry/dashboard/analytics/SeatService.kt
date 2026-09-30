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

	/**
	 * 지난 [days] 일(기준 시각의 전날까지) 동안 그 제품을 쓴 보유 좌석 수 — 모든 보유 좌석이 구성원에 이어지고 관측 가능한 제품일 때만.
	 * 창이 모두 완전하면 0 포함 정확한 수, 아니면 센 수가 있을 때만 그 수다(설정의 관측 인원과 같은 규칙, ADR 0044). 판정할 수 없으면 null.
	 */
	fun activeSeats(tenant: UUID, assessment: SeatAssessment, days: Int): Long? {
		val ledger = assessment.ledger
		val kindOf = ledger.products.associate { it.vendorId to it.kind }
		if (assessment.products.all { it.availability == Availability.UNAVAILABLE }) return null
		if (assessment.held.any { it.memberId == null || kindOf[it.vendorId] !in ledger.observableKinds }) return null
		val (used, complete) = reader.activity(tenant, ledger.asOf, days, ledger.mapping)
		val count = assessment.held.count { (it.memberId!! to kindOf.getValue(it.vendorId)) in used }.toLong()
		return if (complete || count > 0) count else null
	}

	fun roster(tenant: UUID, asOf: Instant) = reader.roster(tenant, asOf)
	fun teamNames(tenant: UUID) = reader.teamNames(tenant)
}
