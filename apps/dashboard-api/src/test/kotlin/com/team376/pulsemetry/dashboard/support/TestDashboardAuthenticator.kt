package com.team376.pulsemetry.dashboard.support

import com.team376.pulsemetry.dashboard.authentication.DashboardAuthenticator
import com.team376.pulsemetry.dashboard.authentication.DashboardPrincipal
import com.team376.pulsemetry.dashboard.authentication.Role
import jakarta.servlet.http.HttpServletRequest
import java.util.UUID

/**
 * **테스트 전용** 인증 구현 (ADR 0022 §3 — 런타임에 주체를 지어내는 설정은 두지 않는다).
 *
 * `Authorization: Test <tenantId>:<memberId>:<role>` 이면 그 주체다. `Test unavailable` 은 저장소 장애를 흉내 내
 * 예외를 올린다. 그 밖은 인증하지 않는다.
 */
class TestDashboardAuthenticator : DashboardAuthenticator {

	override fun authenticate(request: HttpServletRequest): DashboardPrincipal? {
		val value = request.getHeader("Authorization")?.removePrefix(SCHEME) ?: return null
		if (value == UNAVAILABLE) throw IllegalStateException("인증 저장소 장애(테스트)")
		val parts = value.split(':')
		if (parts.size != 3) return null
		val role = Role.entries.firstOrNull { it.wire == parts[2] } ?: return null
		return DashboardPrincipal(UUID.fromString(parts[0]), UUID.fromString(parts[1]), role)
	}

	companion object {
		private const val SCHEME = "Test "
		private const val UNAVAILABLE = "unavailable"

		fun header(principal: DashboardPrincipal): String =
			"$SCHEME${principal.tenantId}:${principal.memberId}:${principal.role.wire}"

		fun unavailableHeader(): String = "$SCHEME$UNAVAILABLE"
	}
}
