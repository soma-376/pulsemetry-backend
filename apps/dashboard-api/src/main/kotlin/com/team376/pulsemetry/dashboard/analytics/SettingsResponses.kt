package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.persistence.enrollment.management.ContractStatus
import com.team376.pulsemetry.persistence.enrollment.seat.SeatSourceView

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

/**
 * 종량 지출 한 기간 (ADR 0050). [actualBilledUsd] 는 벤더 청구 누계, [equivalentCostUsd] 는 이 절에서 내지 않는다(null — 환산 비용은 개요·팀의 제품별 사용이 낸다).
 * 가산: [billingKind](`usage_cost`·`usage_spend`), [finalized](false 면 벤더가 고칠 수 있다), [source](`connector`·`seed`), [fetchedAt](벤더에서 읽은 시각).
 */
data class MeteredPeriod(val startDate: String, val endDate: String, val equivalentCostUsd: String?, val actualBilledUsd: String?,
	val billingKind: String? = null, val finalized: Boolean? = null, val source: String? = null, val fetchedAt: String? = null)

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
	/** 좌석 원천(가산, ADR 0048) — 권위·커넥터 설명·활성 연결. 자격증명은 설정됨 여부와 갱신 시각만 낸다. 연결 상태는 기준 시각이 아니라 현재 값이다. */
	val seatSource: SeatSourceView,
	/** 이 제품의 좌석 수(가산, ADR 0048) — 좌석 원장의 기준 시각 값. 원장을 쓸 수 없으면 unavailable 과 사유. */
	val seats: Section<VendorSeats>,
) {
	/** 계약은 필수 값을 모두 검증한 뒤 저장된다. 유효 기간과 입력 완료는 별개다. */
	val contractInput: ContractInput get() = ContractInput.of(contract)
}


/** 제품 하나의 좌석 — 보유(배정·해제 예정·배정 대기), 유효한 계약의 좌석, 계약 좌석 − 보유(0 미만은 0). 계약이 유효하지 않으면 계약·미배정은 null. */
data class VendorSeats(val assigned: Long, val contracted: Long?, val unallocated: Long?)

/** 편집에 쓰는 정책 값·충돌 확인 판·선택지·마지막 정리 작업만 반환한다. */
data class CollectionPolicy(
	val version: Long,
	val collectRawContent: Boolean,
	val reclaimIdleDays: Int,
	val aggregateRetentionMonths: Int?,
	/** 조직 정책 설정의 판(가산). 저장한 적 없으면 0 이다. 회수 기준·집계 보존을 저장할 때 `expectedSettingsVersion` 으로 보낸다. */
	val settingsVersion: Long,
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

/** 설정 카드에 표시하는 값과 해당 값의 상태. 계약 합계는 유효 계약만 센다. */
data class SettingsSummary(
	val unconfiguredVendors: OverviewResponse.Value<Long>,
	val monthlySeatFeeUsd: OverviewResponse.Value<String>,
	val contractedSeats: OverviewResponse.Value<Long>,
	val activeSeats7d: OverviewResponse.Value<Long>,
	val assignedSeats: OverviewResponse.Value<Long>,
	val meteredMonthToDate: OverviewResponse.Value<String>,
)

/** 목록에 필요한 값만 낸다. 계약 등급·관측·연결·청구 상세는 단건 조회가 맡는다. */
data class SettingsVendorRow(
	val vendorId: String,
	val displayName: String,
	val kind: String,
	val source: String,
	val version: Long,
	val contractStatus: ContractStatus,
	val contractInput: ContractInput,
	val contractSeats: OverviewResponse.Value<Long>,
	val monthlySeatFeeUsd: OverviewResponse.Value<String>,
	val assignedSeats: OverviewResponse.Value<Long>,
)

data class PolicyRollout(
	val desiredVersion: Long,
	val eligibleInstallations: Long,
	val appliedInstallations: Long,
	val outdatedInstallations: Long,
	val unknownInstallations: Long,
	/** 판정 근거별 설치 수(가산) — 설치 목록의 [InstallationRow.appliedEvidence] 와 같은 식이다. */
	val evidence: RolloutEvidence,
)

/** 적용 판정의 근거별 수. 셋의 합은 [PolicyRollout.eligibleInstallations] 다. */
data class RolloutEvidence(val heartbeat: Long, val appliedConfirmation: Long, val none: Long)

data class SettingsResponse(
	val meta: CurrentMeta,
	val capabilities: SettingsCapabilities,
	val summary: SettingsSummary,
	val vendors: Page<SettingsVendorRow>,
	val collectionPolicy: CollectionPolicy,
	val policyRollout: PolicyRollout,
	val alertRules: List<AlertRule>,
)

data class VendorsResponse(val meta: CurrentMeta, val vendors: Page<SettingsVendorRow>)

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
	/** 지금 판의 근거(가산): `heartbeat`(마지막 설치 보고) · `applied_confirmation`(보고가 없어 적용 확인 기록) · `none`. */
	val appliedEvidence: String,
	/** 근거가 적용 확인 기록일 때 그 판을 확인한 시각(가산). 그 밖에는 null. */
	val appliedConfirmedAt: String?,
)

data class InstallationsResponse(val meta: CurrentMeta, val desiredPolicyVersion: Long, val installations: Page<InstallationRow>)

/** 계약 저장 여부. 단가 0은 미입력이 아니며 만료·시작 예정도 입력 완료다. */
data class ContractInput(val status: String, val missingFields: List<String>) {
	companion object {
		fun of(contract: VendorContract?): ContractInput = if (contract == null)
			ContractInput("required", listOf("planId", "seats", "monthlyFeePerSeatUsd"))
		else ContractInput("complete", emptyList())
	}
}

data class ContractInputVendor(val vendorId: String, val displayName: String, val missingFields: List<String>)
/** 현재 갱신이 필요한 최신 계약. 종료일은 KST 날짜이며 당일까지는 유효하다. */
data class ExpiredContract(val vendorId: String, val displayName: String, val endDate: String)

data class ContractInputStatusResponse(
	val organizationId: String,
	val asOf: String,
	val requiredCount: Int,
	val vendors: List<ContractInputVendor>,
	val expiredCount: Int,
	val expiredContracts: List<ExpiredContract>,
)
