package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.connector.vendor.BilledKind
import com.team376.pulsemetry.connector.vendor.Capability
import com.team376.pulsemetry.connector.vendor.ConnectorDescriptors
import com.team376.pulsemetry.dashboard.request.QueryReader
import com.team376.pulsemetry.persistence.enrollment.seat.BillingPeriod
import com.team376.pulsemetry.persistence.enrollment.seat.ConnectionRecord
import com.team376.pulsemetry.persistence.enrollment.seat.SyncStatus
import com.team376.pulsemetry.persistence.enrollment.seat.VendorBillingStore
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 설정의 종량 지출(`meteredMonthToDate`)을 벤더 청구 누계로 낸다 (ADR 0050). 원천은 커넥터가 벤더 비용·지출 API 에서 읽어 저장한 값뿐이다 —
 * 환산 비용·계약액·좌석 단가로 채우지 않는다. 청구 누계는 판이 없는 **현재 값**이다(연결 상태처럼).
 *
 * | 등록 제품 | 가용성 | 사유 |
 * | --- | --- | --- |
 * | 계약 플랜의 커넥터가 청구를 구현하지 않음(커넥터 없는 플랜 포함) | unavailable | `billing_not_supported` |
 * | 활성 연결 없음(또는 연결의 커넥터가 플랜의 것이 아님) | unavailable | `billing_source_not_connected` |
 * | 지금 기간의 누계가 없음(달이 바뀐 뒤 아직 읽지 않음 포함) | unavailable | `billing_sync_pending` · `billing_sync_failing`(실패만) |
 * | 마지막 읽기가 실패 | partial | `billing_sync_failing` |
 * | 누계를 읽은 지 [staleAfter] 넘음 | partial | `billing_sync_outdated` |
 *
 * 사용 비용(Claude Enterprise)은 조직 달력의 이번 달이 기간이고, 사용 지출(Cursor Enterprise)은 벤더의 청구 주기다. [staleAfter] 는 좌석 원장과 같은 신선도 기준이다.
 */
class VendorBilling(private val source: JdbcClient, private val staleAfter: Duration) {

	/** 조직 합계의 사유 — 기간이 서로 다른 벤더를 더하지 않는다. */
	companion object {
		const val NOT_SUPPORTED = "billing_not_supported"
		const val NOT_CONNECTED = "billing_source_not_connected"
		const val PENDING = "billing_sync_pending"
		const val FAILING = "billing_sync_failing"
		const val OUTDATED = "billing_sync_outdated"
		const val PERIODS_DIFFER = "billing_periods_differ"
	}

	/** 제품 하나 — [kind]·[plan] 은 현재 계약의 것이다. */
	data class Product(val vendorId: String, val kind: String, val plan: String?)

	fun sections(tenant: UUID, products: List<Product>, connections: Map<String, ConnectionRecord>, now: Instant): Map<String, Section<MeteredPeriod>> {
		val stored = VendorBillingStore.latest(source, tenant, now)
		val monthStart = now.atZone(QueryReader.SEOUL).toLocalDate().withDayOfMonth(1).atStartOfDay(QueryReader.SEOUL).toInstant()
		return products.associate { product -> product.vendorId to section(product, connections[product.vendorId], stored[product.vendorId], monthStart, now) }
	}

	private fun section(product: Product, connection: ConnectionRecord?, period: BillingPeriod?, monthStart: Instant, now: Instant): Section<MeteredPeriod> {
		val connector = ConnectorDescriptors.forPlan(product.kind, product.plan)
		if (connector == null || Capability.BILLING !in connector.capabilities) return Section(Availability.UNAVAILABLE, NOT_SUPPORTED, null)
		if (connection == null || connection.connector != connector.id) return Section(Availability.UNAVAILABLE, NOT_CONNECTED, null)
		val failing = connection.billingStatus == SyncStatus.FAILING
		// 달력 달이 기간인 누계는 이번 달 시작에서 시작해야 지금 기간이다. 청구 주기는 벤더가 정해 시작을 대조할 수 없다(신선도로 본다).
		val current = period?.takeIf { it.kind != BilledKind.USAGE_COST || it.periodStart == monthStart }
			?: return Section(Availability.UNAVAILABLE, if (failing) FAILING else PENDING, null)
		val (availability, reason) = when {
			failing -> Availability.PARTIAL to FAILING
			current.fetchedAt.plus(staleAfter) < now -> Availability.PARTIAL to OUTDATED
			else -> Availability.AVAILABLE to null
		}
		return Section(availability, reason, MeteredPeriod(
			startDate = current.periodStart.atZone(QueryReader.SEOUL).toLocalDate().toString(),
			endDate = current.periodEnd.atZone(QueryReader.SEOUL).toLocalDate().toString(),
			equivalentCostUsd = null,
			actualBilledUsd = Money.format(current.amountUsd),
			billingKind = current.kind.wire, finalized = current.finalized, source = current.source, fetchedAt = current.fetchedAt.toString(),
		))
	}

	/**
	 * 조직 합계 — 모든 등록 제품이 값을 갖고 기간이 같을 때만 더한다. 한 제품이라도 없으면 부분합을 내지 않는다(그 제품의 사유로 partial, 금액 null).
	 * 기간이 다르면 `billing_periods_differ`. 등록 제품이 없으면 `not_applicable`.
	 */
	fun summary(sections: List<Section<MeteredPeriod>>): Section<MeteredSummary> {
		if (sections.isEmpty()) return Section(Availability.UNAVAILABLE, Availability.NOT_APPLICABLE, null)
		if (sections.all { it.data == null }) return Section(Availability.UNAVAILABLE, sections.first().reason, null)
		sections.firstOrNull { it.data == null }?.let { return Section(Availability.PARTIAL, it.reason, MeteredSummary(null, null)) }
		val periods = sections.map { it.data!!.startDate to it.data.endDate }.toSet()
		if (periods.size > 1) return Section(Availability.PARTIAL, PERIODS_DIFFER, MeteredSummary(null, null))
		val total = sections.sumOf { it.data!!.actualBilledUsd!!.toBigDecimal() }
		val lowered = sections.firstOrNull { it.availability != Availability.AVAILABLE }
		return Section(lowered?.availability ?: Availability.AVAILABLE, lowered?.reason, MeteredSummary(null, Money.format(total)))
	}
}
