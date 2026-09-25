package com.team376.pulsemetry.dashboard.authentication

import java.util.UUID

/**
 * 인증된 대시보드 사용자. 세 값은 ADR 0007 의 AT 클레임(tenant · member · role)과 같다.
 *
 * 역할은 화면 요청서의 어휘([Role])다. 저장소의 역할 값을 이 어휘로 옮기는 것은 포트 구현의 몫이다.
 */
data class DashboardPrincipal(
	val tenantId: UUID,
	val memberId: UUID,
	val role: Role,
)
