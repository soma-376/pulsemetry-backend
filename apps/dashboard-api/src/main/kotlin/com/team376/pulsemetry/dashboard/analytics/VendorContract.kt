package com.team376.pulsemetry.dashboard.analytics

/** 좌석 판정과 설정 응답이 함께 쓰는 계약 값. */
data class VendorTier(val tierId: String, val label: String, val seats: Long, val monthlyFeePerSeatUsd: String)

data class VendorContract(
	val version: Long,
	val planId: String,
	val effectiveFrom: String,
	val effectiveTo: String?,
	val termNote: String?,
	val tiers: List<VendorTier>,
	val monthlySeatFeeUsd: String?,
	val confirmedAt: String,
	val confirmedBy: String,
)

