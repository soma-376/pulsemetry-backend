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

data class ReclaimCandidate(
	val seatAssignmentId: String,
	val memberId: String,
	val account: String,
	val team: TeamRef,
	val vendorId: String,
	val tierId: String,
	val version: Long,
	val lastUsedAt: String?,
	val idleDays: Long,
	val estimatedMonthlySavingsUsd: String?,
	val canReclaim: Boolean,
	val reason: String?,
)

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
