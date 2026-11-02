package com.team376.pulsemetry.enrollment.vendor.application

import java.time.LocalDate

/** 입력 해석은 API가 구현한다. 존재·버전 판정 후에 필요한 값만 읽어 기존 오류 우선순위를 유지한다. */
interface VendorInput {
    val displayName: String
    val expectedVersion: Long
    val kind: String
    val hasContract: Boolean
    val contract: ContractInput
}

interface ContractInput {
    val planId: String
    val effectiveFrom: LocalDate
    val effectiveTo: LocalDate?
    val tiers: List<TierInput>
    val termNote: String?
}

interface TierInput {
    val seats: Long
    val monthlyFeePerSeatUsd: String
    val label: String
}
