package com.team376.pulsemetry.persistence.enrollment

import com.team376.pulsemetry.persistence.enrollment.management.ContractStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class ContractStatusTest {
    private val start = LocalDate.parse("2026-09-01")
    private val end = LocalDate.parse("2026-09-30")

    @Test fun `서울 종료일 당일까지 유효하고 다음 날부터 만료다`() {
        assertThat(ContractStatus.at(start, end, Instant.parse("2026-09-30T14:59:59Z"))).isEqualTo(ContractStatus.active)
        assertThat(ContractStatus.at(start, end, Instant.parse("2026-09-30T15:00:00Z"))).isEqualTo(ContractStatus.expired)
    }
    @Test fun `시작일 전과 시작일 당일을 구분한다`() {
        assertThat(ContractStatus.at(start, end, Instant.parse("2026-08-31T14:59:59Z"))).isEqualTo(ContractStatus.scheduled)
        assertThat(ContractStatus.at(start, end, Instant.parse("2026-08-31T15:00:00Z"))).isEqualTo(ContractStatus.active)
    }
    @Test fun `계약 미입력과 종료일 없는 계약을 구분한다`() {
        val future = Instant.parse("2030-01-01T00:00:00Z")
        assertThat(ContractStatus.at(null, null, future)).isEqualTo(ContractStatus.missing)
        assertThat(ContractStatus.at(start, null, future)).isEqualTo(ContractStatus.active)
    }
}
