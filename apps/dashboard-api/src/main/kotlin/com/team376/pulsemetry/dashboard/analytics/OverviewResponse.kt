package com.team376.pulsemetry.dashboard.analytics

/** 개요 응답 (개요 명세 3절 `OverviewResponse`). 모든 키를 유지하고 산출할 수 없는 값은 null 이다. */
data class OverviewResponse(
	val meta: Meta,
	val comparison: Comparison,
	val ingest: Ingest,
	val usage: UsagePair,
	val seats: Seats,
	val alerts: Alerts,
	val trend: Trend,
	val modelMix: ModelMix,
	val waste: Waste,
	val teamUsage: TeamUsage,
) {
	data class Meta(
		val organizationId: String,
		val generatedAt: String,
		val dataThrough: String?,
		val currency: String,
		val startDate: String,
		val endDate: String,
		val timeZone: String,
		val dayCount: Int,
		val dataState: String,
		val currentCoverage: Coverage,
		val pricingVersion: String?,
	)

	data class Comparison(
		val mode: String,
		val startDate: String?,
		val endDate: String?,
		val status: String,
		val reason: String?,
		val coverage: Coverage?,
	)

	data class Ingest(
		val status: String,
		val reason: String?,
		val asOf: String,
		val firstObservedAt: String?,
		val lastReceivedAt: String?,
		val windowMinutes: Int,
		val activeInstallations: Long?,
		val observedMembers: Long?,
		val eligibleMembers: Long?,
		val coverageRatio: Double?,
	)

	data class UsagePair(val current: Usage?, val previous: Usage?)

	data class Seats(
		val availability: String,
		val reason: String?,
		val scopeVendorIds: List<String>,
		val allocationMethod: String?,
		val current: SeatPeriod?,
		val previous: SeatPeriod?,
		val reclaimEstimate: ReclaimEstimate?,
	)

	data class SeatPeriod(
		val contractedSeats: Long,
		val activeSeats: Long?,
		val monthlyFeeUsd: String,
		val allocatedFeeUsd: String,
		val equivalentCostUsd: String,
		val efficiency: Double?,
	)

	data class ReclaimEstimate(val idleSeats: Long, val monthlySavingsUsd: String, val efficiencyAfterReclaim: Double?)

	data class Alerts(
		val availability: String,
		val reason: String?,
		val asOf: String,
		val unacknowledgedTotal: Long?,
		val security: Long?,
		val cost: Long?,
	)

	data class Trend(val bucket: String, val points: List<TrendPoint>)

	data class TrendPoint(
		val date: String,
		val observation: String,
		val equivalentCostUsd: String?,
		val allocatedSeatCostUsd: String?,
		val totalTokens: Long?,
	)

	data class ModelMix(val availability: String, val reason: String?, val models: List<ModelShare>)

	data class ModelShare(
		val modelId: String,
		val displayName: String,
		val equivalentCostUsd: String?,
		val totalTokens: Long?,
		val effectiveCostPerMillionTokensUsd: String?,
	)

	data class Waste(
		val availability: String,
		val reason: String?,
		val methodologyVersion: String?,
		val totalMonthlyEquivalentCostUsd: String?,
		val items: List<WasteItem>,
	)

	data class WasteItem(
		val kind: String,
		val availability: String,
		val reason: String?,
		val currentEquivalentCostUsd: String?,
		val previousEquivalentCostUsd: String?,
		val monthlyEquivalentCostUsd: String?,
		val previousMonthlyEquivalentCostUsd: String?,
		val rate: Double?,
		val rateDefinition: String?,
	)

	data class TeamUsage(
		val availability: String,
		val reason: String?,
		val ranking: String,
		val attributionBasis: String,
		val totalTeamCount: Int,
		val topTeams: List<TopTeam>,
		val otherTeams: OtherTeams,
		val unassigned: Unassigned,
	)

	data class TopTeam(
		val teamId: String,
		val teamName: String,
		val current: TeamPeriod,
		val previous: TeamPeriod?,
		val topModel: TopModel?,
	)

	data class OtherTeams(val count: Int, val currentEquivalentCostUsd: String?, val previousEquivalentCostUsd: String?)

	data class Unassigned(val current: TeamPeriod, val previous: TeamPeriod?)
}
