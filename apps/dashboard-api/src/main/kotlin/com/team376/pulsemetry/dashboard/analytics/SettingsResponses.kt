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

/**
 * `version`·`effectiveAt`·`updatedBy` 는 원문 선택이 실린 manifest 의 것이다. 회수 기준·집계 보존은 manifest 와 따로 저장하고
 * 판도 따로 센다(ADR 0046) — 그 판·저장 시각·저장한 사람은 가산 필드 `settings*` 다. `rawContentRetentionDays` 는 원천이 없어 null 이다.
 */
data class CollectionPolicy(
	val version: Long,
	val collectRawContent: Boolean,
	val reclaimIdleDays: Int,
	val aggregateRetentionMonths: Int?,
	val rawContentRetentionDays: Int?,
	val effectiveAt: String,
	val updatedBy: String,
	/** 조직 정책 설정의 판(가산). 저장한 적 없으면 0 이다. 회수 기준·집계 보존을 저장할 때 `expectedSettingsVersion` 으로 보낸다. */
	val settingsVersion: Long,
	val settingsUpdatedAt: String?,
	val settingsUpdatedBy: String?,
	/** `organization` 이면 조직이 저장한 회수 기준, `default` 면 서버 기본 설정이다(가산). */
	val reclaimIdleDaysSource: String,
	/** 저장할 수 있는 값(가산). 집계 보존의 null 은 무기한이다. */
	val options: PolicyOptions,
	/** 이 조직의 가장 최근 보존 정리 작업(가산 — ADR 0047). 진행은 작업 상태 조회로 본다. 없으면 null. */
	val cleanupOperationId: String?,
)

data class PolicyOptions(val reclaimIdleDays: List<Int>, val aggregateRetentionMonths: List<Int?>)

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
