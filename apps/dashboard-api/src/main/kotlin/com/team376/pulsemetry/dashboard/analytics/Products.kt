package com.team376.pulsemetry.dashboard.analytics

/**
 * 카탈로그 제품별 사용 (ADR 0045) — 개요·팀 목록·팀 상세의 가산 필드. 관측 제품은 snapshot 에 복제한 명시 매핑으로만 카탈로그 제품에 잇는다(ADR 0044).
 * [kind] 가 null 이면 매핑 없는 관측이다(어느 등록 제품에도 넣지 않는다). 값은 [UsageTotals] 의 null 규칙을 그대로 따른다 — 부분합을 만들지 않는다.
 */
data class ProductUsage(
	val kind: String?,
	val displayName: String?,
	val activeUsers: Long?,
	val sessionCount: Long?,
	val totalTokens: Long?,
	val equivalentCostUsd: String?,
)

/** 관측된 제품의 이름만. [kind] 가 null 이면 매핑 없는 관측이다. */
data class ProductRef(val kind: String?, val displayName: String?)

object Products {

	/** 제품 축 집계(키 = 카탈로그 제품 ID 또는 매핑 없음)를 카탈로그 순서로, 매핑 없는 관측은 끝에 둔다. 사용량 행이 없는 제품은 넣지 않는다. */
	fun usages(totals: Map<String, UsageTotals>, mapping: List<SnapshotReferences.Product>, pricingMixed: Boolean): List<ProductUsage> =
		ordered(totals.filterValues { it.hasUsage }.keys, mapping).map { ref ->
			val group = totals.getValue(ref.kind ?: UsageAggregator.UNMAPPED_PRODUCT)
			ProductUsage(ref.kind, ref.displayName, group.activeUsers(), group.sessionCount(), group.apiTotal(),
				group.equivalentCost(pricingMixed)?.let(Money::format))
		}

	fun refs(keys: Collection<String>, mapping: List<SnapshotReferences.Product>): List<ProductRef> = ordered(keys, mapping)

	private fun ordered(keys: Collection<String>, mapping: List<SnapshotReferences.Product>): List<ProductRef> {
		val catalog = mapping.distinctBy { it.kind }
		val order = catalog.withIndex().associate { it.value.kind to it.index }
		val names = catalog.associate { it.kind to it.displayName }
		return keys.distinct().sortedWith(compareBy<String> { if (it == UsageAggregator.UNMAPPED_PRODUCT) Int.MAX_VALUE else order[it] ?: Int.MAX_VALUE - 1 }.thenBy { it })
			.map { key -> if (key == UsageAggregator.UNMAPPED_PRODUCT) ProductRef(null, null) else ProductRef(key, names[key] ?: key) }
	}
}
