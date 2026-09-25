package com.team376.pulsemetry.dashboard.organization

import com.team376.pulsemetry.persistence.enrollment.repository.TenantRepository
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 조직을 읽는다. 삭제 표시(`deleted_at`)가 있으면 없는 조직이다. 읽기만 한다 — 쓰기 소유는 enrollment 다(ADR 0022 §2).
 */
@Component
class OrganizationReader(
	private val tenants: TenantRepository,
) {

	fun find(id: UUID): Organization? =
		tenants.findById(id).orElse(null)
			?.takeIf { it.deletedAt == null }
			?.let { Organization(id = it.id, name = it.name, timeZone = it.timezone) }
}
