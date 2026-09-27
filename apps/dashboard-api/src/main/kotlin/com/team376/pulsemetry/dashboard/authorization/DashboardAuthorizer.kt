package com.team376.pulsemetry.dashboard.authorization

import com.team376.pulsemetry.dashboard.authentication.DashboardPrincipal
import java.util.UUID

/**
 * 인가 포트 (ADR 0022 §3). 주체가 조직의 행위를 할 수 있는지 판정한다.
 *
 * [teamId] 는 팀 하나를 겨냥한 행위(팀 상세·팀 사용자)일 때만 온다. 팀 범위 정책(팀 리드 등)이 생기면 이 값으로 판정한다.
 * **조직 일치는 이 포트 밖에서 먼저 검사한다**(`OrganizationAccess`) — 정책이 무엇이든 다른 조직을 허용하지 않는다.
 */
fun interface DashboardAuthorizer {

	fun isAllowed(principal: DashboardPrincipal, organizationId: UUID, action: DashboardAction, teamId: UUID?): Boolean
}
