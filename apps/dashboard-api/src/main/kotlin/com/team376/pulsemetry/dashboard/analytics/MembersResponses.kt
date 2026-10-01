package com.team376.pulsemetry.dashboard.analytics

/** 구성원 명세의 타입. 이름·타입·nullable 은 요청서의 TypeScript 타입과 같다. */
data class Member(
	val memberId: String,
	val account: String,
	val displayName: String,
	val team: TeamRef,
	val role: String,
	val status: String,
	val version: Long,
	val periodUsage: Usage?,
	val lastUsedAt: String?,
	val observation: String,
	val seatState: String,
)

data class SeatSummary(
	val contracted: Long,
	val assigned: Long,
	val unallocated: Long,
	val activeInPeriod: Long?,
	val inactiveAssigned: Long?,
	val reclaimCandidates: Long?,
	val estimatedMonthlySavingsUsd: String?,
)

data class MemberSummary(
	val rosterMembers: Long,
	val activeUsers: Long?,
	val unassignedMembers: Long,
	val periodUnassignedEquivalentCostUsd: String?,
	val periodTotalEquivalentCostUsd: String?,
	val seats: Section<SeatSummary>,
)

/**
 * 회수 후보 (ADR 0048 §7). `account` 는 구성원의 계정(이메일), `vendorAccount` 는 좌석의 벤더 계정(가산 — Copilot 은 GitHub 로그인).
 * `tierId` 는 모르면 null 이다(요청서는 문자열 — 등급을 주지 않는 벤더의 좌석은 등급을 모른다). 절감액은 계약의 해지·감액 조건 원천이 없어 null 이다.
 */
data class ReclaimCandidate(
	val seatAssignmentId: String,
	val memberId: String,
	val account: String,
	val team: TeamRef,
	val vendorId: String,
	val tierId: String?,
	val version: Long,
	val lastUsedAt: String?,
	val idleDays: Long,
	val estimatedMonthlySavingsUsd: String?,
	val canReclaim: Boolean,
	val reason: String?,
	val vendorAccount: String,
	/** 회수할 수 있을 때의 실행 방식(가산, ADR 0049) — `vendor_control`·`admin_action`. 불가면 null. */
	val reclaimMethod: String? = null,
)

/**
 * 구성원 상세의 벤더 좌석 한 자리 (`GET O/members/{memberId}/seats`). 좌석 원장의 기준 시각 값과 그 판정(관측·검토·회수 가능)을 싣는다.
 * `lastUsedAt` 은 그 제품의 도구 사용(관측 제품 매핑)과 벤더 활동 중 늦은 것, `idleDays` 는 관측할 수 있을 때만 있다.
 */
data class MemberSeat(
	val seatAssignmentId: String,
	val version: Long,
	val vendorId: String,
	val vendorName: String,
	val kind: String,
	val contractVersion: Long?,
	val tierId: String?,
	val tierLabel: String?,
	val vendorTier: String?,
	val account: String,
	val accountKind: String,
	val state: String,
	val source: String,
	val memberLink: String?,
	val assignedAt: String,
	val releaseEffectiveOn: String?,
	val releasedAt: String?,
	val ledgerAvailability: String,
	val ledgerReason: String?,
	val lastUsedAt: String?,
	val idleDays: Long?,
	val reviewReason: String?,
	val reclaimCandidate: Boolean,
	val canReclaim: Boolean,
	val reclaimReason: String?,
	/** 회수할 수 있을 때의 실행 방식(가산, ADR 0049). 불가면 null. */
	val reclaimMethod: String? = null,
	/** 이 좌석의 가장 최근 회수·복원 작업(가산, ADR 0049) — 상태는 작업 조회로 본다. 없으면 null. */
	val lastControl: SeatControlRef? = null,
)

data class SeatControlRef(val operationId: String, val kind: String)

/**
 * 등록 제품 하나의 좌석 (`GET O/vendors/{vendorId}/seats`, ADR 0048·0049) — 구성원에 잇지 않은 좌석까지. 좌석 입력·회수 화면이 쓴다. 현재 상태다.
 * `memberAccount` 는 이은 구성원의 계정(로스터에 없으면 null), `ledgerAvailability`·`ledgerReason` 은 그 제품의 원장 가용성이다.
 */
data class VendorSeatsResponse(val meta: CurrentMeta, val vendorId: String, val ledgerAvailability: String, val ledgerReason: String?, val seats: Page<VendorSeatItem>)

data class VendorSeatItem(
	val seatAssignmentId: String,
	val version: Long,
	val account: String,
	val accountKind: String,
	val state: String,
	val source: String,
	val memberId: String?,
	val memberAccount: String?,
	val memberLink: String?,
	val tierId: String?,
	val tierLabel: String?,
	val vendorTier: String?,
	val assignedAt: String,
	val releaseEffectiveOn: String?,
	val releasedAt: String?,
	val note: String?,
	val canReclaim: Boolean,
	val reclaimReason: String?,
	val reclaimMethod: String?,
	val lastControl: SeatControlRef?,
)

data class MemberSeatsResponse(val meta: CurrentMeta, val memberId: String, val policy: IdlePolicy, val seats: List<MemberSeat>)

data class IdlePolicy(val idleDays: Int, val version: Long)

data class MemberCapabilities(val invite: Boolean, val assignTeam: Boolean, val reclaimSeats: Boolean, val restoreSeats: Boolean)

data class MembersResponse(
	val meta: AnalyticsMeta,
	val asOf: String,
	val ingest: OverviewResponse.Ingest,
	val summary: MemberSummary,
	val policy: IdlePolicy,
	val capabilities: MemberCapabilities,
	val members: Page<Member>,
	val unassigned: Page<Member>,
	val reclaimCandidates: Section<Page<ReclaimCandidate>>,
)

data class MemberListResponse(val meta: AnalyticsMeta, val members: Page<Member>)

data class ReclaimCandidatesResponse(
	val meta: CurrentMeta,
	val idleDays: Int,
	val candidates: Section<Page<ReclaimCandidate>>,
	/** 이 목록이 쓴 조직의 회수 기준과 그 판(가산 — ADR 0046). 구성원 화면의 `policy` 와 같다. */
	val policy: IdlePolicy,
)
