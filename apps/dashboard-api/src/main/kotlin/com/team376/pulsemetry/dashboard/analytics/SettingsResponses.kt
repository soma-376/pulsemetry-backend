package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.persistence.enrollment.management.ContractStatus

/** 설정 명세의 타입. 이름·타입·nullable 은 요청서의 TypeScript 타입과 같다. */
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

data class MeteredPeriod(val startDate: String, val endDate: String, val equivalentCostUsd: String, val actualBilledUsd: String?)

data class VendorCheck(val code: String, val severity: String)

data class Vendor(
	val vendorId: String,
	val displayName: String,
	val kind: String,
	val source: String,
	val version: Long,
	val firstSeenAt: String?,
	val lastSeenAt: String?,
	val activeUsers7d: Long?,
	val activeUsers30d: Long?,
	val observation: String,
	val state: String,
	val contract: VendorContract?,
	val contractStatus: ContractStatus,
	val meteredMonthToDate: Section<MeteredPeriod>,
	val checks: List<VendorCheck>,
)

data class CollectionPolicy(
	val version: Long,
	val collectRawContent: Boolean,
	val reclaimIdleDays: Int,
	val aggregateRetentionMonths: Int?,
	val rawContentRetentionDays: Int?,
	val effectiveAt: String,
	val updatedBy: String,
)

data class AlertThreshold(val value: Double, val unit: String)

data class AlertRule(
	val ruleId: String,
	val version: Long,
	val enabled: Boolean,
	val availability: String,
	val reason: String?,
	val threshold: AlertThreshold,
	val evaluationWindow: String,
	val comparisonWindow: String?,
)

data class SettingsCapabilities(
	val editContracts: Boolean,
	val editCollectionPolicy: Boolean,
	val editAlertRules: Boolean,
	val notifyInstallations: Boolean,
)

data class MeteredSummary(val equivalentCostUsd: String?, val actualBilledUsd: String?)

data class SettingsSummary(
	val configuredVendors: Long,
	val unconfiguredVendors: Long,
	val monthlySeatFeeUsd: String?,
	val contractedSeats: Long?,
	val activeSeats7d: Long?,
	val meteredMonthToDate: Section<MeteredSummary>,
	/** 매핑으로 관측됐지만 조직이 등록하지 않은 카탈로그 제품(가산 — ADR 0044). 벤더 목록에는 넣지 않는다. */
	val detectedProducts: List<DetectedProduct>,
	/** 어느 카탈로그 제품에도 매핑되지 않은 관측(가산 — ADR 0044). 없으면 null. */
	val unmappedObservations: UnmappedObservations?,
)

data class DetectedProduct(
	val kind: String,
	val displayName: String,
	val state: String,
	val firstSeenAt: String?,
	val lastSeenAt: String?,
	val activeUsers7d: Long?,
	val activeUsers30d: Long?,
	val observation: String,
)

data class UnmappedObservations(
	/** 관측 행의 `product` 값(수집 어댑터의 어휘). */
	val observedProducts: List<String>,
	val firstSeenAt: String,
	val lastSeenAt: String,
	val activeUsers7d: Long?,
	val activeUsers30d: Long?,
	val observation: String,
)

data class CatalogKind(val kind: String, val displayName: String)

data class CatalogPlan(val planId: String, val kind: String, val displayName: String, val billing: String, val separateUsageBilling: Boolean)

data class Catalog(val kinds: List<CatalogKind>, val plans: List<CatalogPlan>)

data class PolicyRollout(
	val desiredVersion: Long,
	val eligibleInstallations: Long,
	val appliedInstallations: Long,
	val outdatedInstallations: Long,
	val unknownInstallations: Long,
)

data class SettingsResponse(
	val meta: CurrentMeta,
	val ingest: OverviewResponse.Ingest,
	val capabilities: SettingsCapabilities,
	val summary: SettingsSummary,
	val catalog: Catalog,
	val vendors: Page<Vendor>,
	val collectionPolicy: CollectionPolicy,
	val policyRollout: PolicyRollout,
	val alertRules: List<AlertRule>,
)

data class VendorsResponse(val meta: CurrentMeta, val vendors: Page<Vendor>)

data class VendorResponse(val meta: CurrentMeta, val vendor: Vendor)

data class InstallationRow(
	val installationId: String,
	val memberId: String?,
	val account: String?,
	val team: TeamRef,
	val agentVersion: String?,
	val appliedPolicyVersion: Long?,
	val lastHeartbeatAt: String?,
	val canNotify: Boolean,
)

data class InstallationsResponse(val meta: CurrentMeta, val desiredPolicyVersion: Long, val installations: Page<InstallationRow>)
