package com.team376.pulsemetry.dashboard.authorization

import com.team376.pulsemetry.dashboard.authentication.DashboardPrincipal
import com.team376.pulsemetry.dashboard.authentication.Role
import java.util.UUID

/**
 * 합의 전 기본 정책 — **조직 관리자만 모든 조회를 한다**(ADR 0022 §3). 그 밖의 역할은 행위와 팀을 가리지 않고 거부한다.
 *
 * 팀 리드의 팀 범위, 일반 구성원의 개인 목록 공개는 RBAC 합의 뒤에 이 정책을 대체하는 구현이 정한다.
 */
class AdminOnlyDashboardAuthorizer : DashboardAuthorizer {

	override fun isAllowed(
		principal: DashboardPrincipal,
		organizationId: UUID,
		action: DashboardAction,
		teamId: UUID?,
	): Boolean = principal.role == Role.ADMIN
}
