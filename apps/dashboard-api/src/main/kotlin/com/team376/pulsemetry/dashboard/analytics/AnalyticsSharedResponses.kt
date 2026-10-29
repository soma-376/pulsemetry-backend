package com.team376.pulsemetry.dashboard.analytics

/** 다른 분석 화면과 수집 현황이 공유하는 계약. */
data class AnalyticsPeriodMeta(
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
)

data class AnalyticsComparison(
	val mode: String,
	val startDate: String?,
	val endDate: String?,
	val status: String,
	val reason: String?,
	val coverage: Coverage?,
)

data class AnalyticsIngest(
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

data class AnalyticsUsagePair(val current: Usage?, val previous: Usage?)

data class AnalyticsAlerts(
	val availability: String,
	val reason: String?,
	val asOf: String,
	val unacknowledgedTotal: Long?,
	val security: Long?,
	val cost: Long?,
)
