package com.team376.pulsemetry.dashboard.analytics

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SettingsValuesTest {
	@Test
	fun `원천의 부분 가용성과 사유를 보존하고 값 없는 부분합은 불가로 낸다`() {
		val stale = Section(Availability.PARTIAL, "billing_sync_outdated", MeteredSummary(null, "12.50"))
		val shown = SettingsValues.section(stale, stale.data!!.actualBilledUsd)
		assertThat(shown.value).isEqualTo("12.50")
		assertThat(shown.status).isEqualTo("partial")
		assertThat(shown.reasons).containsExactly("billing_sync_outdated")
		val missing = SettingsValues.section<String>(Section<MeteredSummary>(Availability.PARTIAL, "billing_periods_differ", null), null)
		assertThat(missing.value).isNull()
		assertThat(missing.status).isEqualTo("unavailable")
		assertThat(missing.reasons).containsExactly("billing_periods_differ")
	}

	@Test
	fun `0원 계약은 정상 값이고 제외한 계약이 있는 합계는 부분 값이다`() {
		assertThat(SettingsValues.value("0.000000").status).isEqualTo("complete")
		val activeOnly = SettingsValues.value("0.000000", listOf("contract_missing", "contract_expired", "contract_missing"))
		assertThat(activeOnly.status).isEqualTo("partial")
		assertThat(activeOnly.reasons).containsExactly("contract_missing", "contract_expired")
		assertThat(SettingsValues.value<Long>(null).status).isEqualTo("unavailable")
	}
}
