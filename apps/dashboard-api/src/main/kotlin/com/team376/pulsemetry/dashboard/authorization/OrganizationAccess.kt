package com.team376.pulsemetry.dashboard.authorization

import com.team376.pulsemetry.dashboard.authentication.DashboardPrincipal
import com.team376.pulsemetry.dashboard.error.DashboardException
import com.team376.pulsemetry.dashboard.error.ErrorCode
import com.team376.pulsemetry.dashboard.error.FieldErrorCode
import com.team376.pulsemetry.dashboard.organization.Organization
import com.team376.pulsemetry.dashboard.organization.OrganizationReader
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 모든 조직 경로의 공통 관문 — 경로의 `organizationId` 를 해석하고, 주체가 그 조직의 행위를 할 수 있는지 확인하고, 조직을 읽는다.
 *
 * 순서가 응답을 정한다.
 * 1. `organizationId` 가 UUID 가 아니면 400 `invalid_request`(필드 `organizationId`).
 * 2. 주체의 조직이 아니면 **403** — 그 조직이 있는지 보지 않는다. 존재 여부가 응답에 드러나지 않는다.
 * 3. 인가 포트가 거부하면 403.
 * 4. 자기 조직이 없거나 삭제 표시가 있으면 404.
 *
 * 매 요청 다시 판정한다. snapshot 이 있어도 이 검사를 건너뛰지 않는다(허브 ADR 0007 §4 — snapshotId 는 권한 증명이 아니다).
 */
@Component
class OrganizationAccess(
	private val authorizer: DashboardAuthorizer,
	private val organizations: OrganizationReader,
) {

	fun require(
		principal: DashboardPrincipal,
		organizationId: String,
		action: DashboardAction,
		teamId: UUID? = null,
	): Organization {
		val id = parse(organizationId)
		if (id != principal.tenantId) throw DashboardException(ErrorCode.FORBIDDEN)
		if (!authorizer.isAllowed(principal, id, action, teamId)) throw DashboardException(ErrorCode.FORBIDDEN)
		return organizations.find(id) ?: throw DashboardException(ErrorCode.NOT_FOUND)
	}

	private fun parse(value: String): UUID =
		runCatching { UUID.fromString(value) }.getOrNull()?.takeIf { it.toString() == value.lowercase() }
			?: throw DashboardException.invalid(FIELD, FieldErrorCode.INVALID_FORMAT)

	private companion object {
		const val FIELD = "organizationId"
	}
}
