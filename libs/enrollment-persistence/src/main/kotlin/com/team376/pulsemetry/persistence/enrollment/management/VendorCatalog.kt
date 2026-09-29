package com.team376.pulsemetry.persistence.enrollment.management

import org.springframework.jdbc.core.simple.JdbcClient
import java.security.MessageDigest

/** 공통 카탈로그의 조회와 입력 검증 원천. 매번 DB를 읽으며 앱이 조립한다(ADR 0035). */
class VendorCatalog(private val jdbc: JdbcClient) {
    data class Plan(val id: String, val displayName: String, val billing: String, val separateUsageBilling: Boolean)
    data class Product(val id: String, val provider: String, val displayName: String, val product: String,
        val allowsSeatTiers: Boolean, val plans: List<Plan>)
    data class Snapshot(val products: List<Product>) {
        // 길이를 포함해 값 구분자의 충돌을 막는다. 공개 내용이 바뀌면 진행 중인 커서가 무효화된다.
        val version: String = MessageDigest.getInstance("SHA-256").run {
            fun field(value: Any) {
                val bytes = value.toString().toByteArray(Charsets.UTF_8)
                update("${bytes.size}:".toByteArray(Charsets.US_ASCII))
                update(bytes)
            }
            field(products.size)
            products.forEach { p ->
                listOf(p.id, p.provider, p.displayName, p.product, p.allowsSeatTiers, p.plans.size).forEach(::field)
                p.plans.forEach { plan -> listOf(plan.id, plan.displayName, plan.billing, plan.separateUsageBilling).forEach(::field) }
            }
            digest().joinToString("") { "%02x".format(it) }
        }
    }

    /** 한 SQL 문장의 MVCC 스냅샷으로 제품과 플랜을 함께 읽는다. 선택 가능한 행만 반환한다. */
    fun snapshot(): Snapshot {
        val rows = jdbc.sql("""
            SELECT p.id, p.vendor_id, p.display_name, p.product, p.allows_seat_tiers,
                   f.id AS plan_id, f.display_name AS plan_name, f.billing, f.separate_usage_billing
            FROM enrollment.vendor_catalog_products p
            LEFT JOIN enrollment.vendor_catalog_plans f ON f.product_id=p.id AND f.active
            WHERE p.active
            ORDER BY p.sort_order, p.id, f.sort_order, f.id
        """).query { r, _ ->
            Product(r.getString("id"), r.getString("vendor_id"), r.getString("display_name"),
                r.getString("product"), r.getBoolean("allows_seat_tiers"), emptyList()) to
                r.getString("plan_id")?.let { Plan(it, r.getString("plan_name"), r.getString("billing"), r.getBoolean("separate_usage_billing")) }
        }.list()
        return Snapshot(rows.groupBy { it.first }.map { (product, plans) -> product.copy(plans = plans.mapNotNull { it.second }) })
    }

    fun find(id: String): Product? = snapshot().products.find { it.id == id }
}
