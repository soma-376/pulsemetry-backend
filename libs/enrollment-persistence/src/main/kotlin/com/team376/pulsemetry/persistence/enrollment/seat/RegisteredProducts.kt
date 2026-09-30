package com.team376.pulsemetry.persistence.enrollment.seat

import com.team376.pulsemetry.persistence.enrollment.management.ManagementException
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** 계약 등급 하나 — ID·표시 이름·구매 수량. */
internal data class ContractTier(val id: String, val label: String, val seats: Long)

/** 등록 제품과 그 현재 계약의 플랜·등급. 좌석 원장과 연결의 쓰기는 이 행을 잠가 제품 단위로 직렬화한다 (ADR 0048). */
internal data class RegisteredProduct(val vendorId: String, val kind: String, val plan: String?, val tiers: List<ContractTier>) {
	val tierIds: Set<String> get() = tiers.map { it.id }.toSet()

	/** 계약의 구매 수량 합. 계약이 없으면 null(수량을 모른다). */
	val contractedSeats: Long? get() = tiers.takeIf { it.isNotEmpty() }?.sumOf { it.seats }
}

internal object RegisteredProducts {
	/** 보관하지 않은 등록 제품을 잠근다. 없거나 다른 조직의 것이면 404. */
	fun lock(jdbc: JdbcClient, tenant: UUID, vendorId: String): RegisteredProduct = find(jdbc, tenant, vendorId, lock = true)

	fun find(jdbc: JdbcClient, tenant: UUID, vendorId: String, lock: Boolean): RegisteredProduct = jdbc.sql("""
		SELECT v.kind, c.contract->>'planId' AS plan, c.contract::text AS contract
		FROM enrollment.managed_vendors v
		JOIN LATERAL (SELECT contract, archived FROM enrollment.vendor_contract_versions
		              WHERE tenant_id = v.tenant_id AND vendor_id = v.vendor_id ORDER BY version DESC LIMIT 1) c ON true
		WHERE v.tenant_id = :tenant AND v.vendor_id = :id AND NOT v.archived AND NOT c.archived
	""" + if (lock) " FOR UPDATE OF v" else "").param("tenant", tenant).param("id", vendorId)
		.query { rs, _ -> RegisteredProduct(vendorId, rs.getString("kind"), rs.getString("plan"), tiers(rs.getString("contract"))) }
		.optional().orElse(null) ?: throw ManagementException("not_found", 404, "vendorId")

	private val mapper = JsonMapper.builder().build()

	/** 계약 JSON 의 `tiers[]` (저장 명령이 검증해 넣은 값이다). */
	private fun tiers(contract: String?): List<ContractTier> {
		val tiers = contract?.let { mapper.readTree(it).path("tiers") } ?: return emptyList()
		return (0 until tiers.size()).map { tiers.get(it) }.map { ContractTier(it.path("tierId").asString(), it.path("label").asString(), it.path("seats").asLong()) }
	}

	/** 조직이 활성이고 행위자가 그 조직의 활성 owner/admin 인지 다시 확인한다 — 경로의 ID 만 믿지 않는다. */
	fun requireManager(jdbc: JdbcClient, tenant: UUID, actor: UUID) {
		val role = jdbc.sql("""SELECT m.role::text FROM enrollment.members m JOIN enrollment.tenants t ON t.id = m.tenant_id
			WHERE t.id = :tenant AND t.status = 'active' AND t.deleted_at IS NULL AND m.id = :actor AND m.status = 'active'""")
			.param("tenant", tenant).param("actor", actor).query(String::class.java).optional().orElse(null)
		if (role !in setOf("owner", "admin")) throw ManagementException("forbidden", 403)
	}
}
