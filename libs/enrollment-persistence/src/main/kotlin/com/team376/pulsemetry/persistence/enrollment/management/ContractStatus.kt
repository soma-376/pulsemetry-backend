package com.team376.pulsemetry.persistence.enrollment.management

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 조회 기준 시각에 계산하는 계약 기간 상태. 종료일 당일까지 유효하다. */
enum class ContractStatus {
    missing, scheduled, active, expired;

    companion object {
        fun at(from: LocalDate?, to: LocalDate?, asOf: Instant): ContractStatus {
            if (from == null) return missing
            val today = asOf.atZone(ZoneId.of("Asia/Seoul")).toLocalDate()
            return when {
                today < from -> scheduled
                to != null && today > to -> expired
                else -> active
            }
        }
    }
}
