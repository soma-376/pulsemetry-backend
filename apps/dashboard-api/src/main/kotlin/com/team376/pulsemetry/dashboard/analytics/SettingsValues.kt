package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.persistence.enrollment.management.ContractStatus

/** 설정 카드·목록의 수치를 표현한다. 원천의 부분 값과 모르는 값을 정상 합계로 승격하지 않는다. */
object SettingsValues {
	fun <T> value(value: T?, reasons: List<String> = emptyList()): OverviewResponse.Value<T> {
		val distinct = reasons.distinct()
		return OverviewResponse.Value(value, when {
			value == null -> "unavailable"
			distinct.isNotEmpty() -> "partial"
			else -> "complete"
		}, if (value == null && distinct.isEmpty()) listOf("value_unavailable") else distinct)
	}

	fun <T> section(section: Section<*>, value: T?): OverviewResponse.Value<T> = value(value,
		if (section.availability == Availability.AVAILABLE) emptyList()
		else listOf(section.reason ?: "source_not_available"))

	fun vendorRow(vendor: Vendor): SettingsVendorRow = SettingsVendorRow(
		vendor.vendorId, vendor.displayName, vendor.kind, vendor.source, vendor.version, vendor.contractStatus, vendor.contractInput,
		value(vendor.contract?.tiers?.sumOf { it.seats }, if (vendor.contract == null) listOf("contract_missing") else emptyList()),
		value(vendor.contract?.monthlySeatFeeUsd, when {
			vendor.contract == null -> listOf("contract_missing")
			vendor.contract.monthlySeatFeeUsd == null -> listOf("contract_amount_unavailable")
			else -> emptyList()
		}),
		section(vendor.seats, vendor.seats.data?.assigned),
	)

	fun summary(vendors: List<Vendor>, assessment: SeatAssessment, activeSeats: OverviewResponse.Value<Long>, billed: Section<MeteredSummary>): SettingsSummary {
		val active = vendors.filter { it.contractStatus == ContractStatus.active }
		val excluded = vendors.filter { it.contractStatus != ContractStatus.active }.map { "contract_${it.contractStatus}" }.distinct()
		val amount = active.takeIf { it.all { vendor -> vendor.contract?.monthlySeatFeeUsd != null } }
			?.sumOf { it.contract!!.monthlySeatFeeUsd!!.toBigDecimal() }?.let(Money::format)
		val assigned = assessment.held.size.toLong().takeIf { assessment.products.any { it.availability != Availability.UNAVAILABLE } }
		val seatReasons = assessment.products.filter { it.availability != Availability.AVAILABLE }.map { it.reason ?: "seat_source_unavailable" }
		return SettingsSummary(
			unconfiguredVendors = value(vendors.count { it.contractStatus != ContractStatus.active }.toLong()),
			monthlySeatFeeUsd = value(amount, excluded + if (amount == null) listOf("contract_amount_unavailable") else emptyList()),
			contractedSeats = value(active.sumOf { it.contract!!.tiers.sumOf { tier -> tier.seats } }, excluded),
			activeSeats7d = activeSeats,
			assignedSeats = value(assigned, if (assessment.products.isEmpty()) listOf("not_applicable") else seatReasons),
			meteredMonthToDate = section(billed, billed.data?.actualBilledUsd),
		)
	}
}
