package com.team376.pulsemetry.enrollment.api

import com.team376.pulsemetry.enrollment.contract.CreateInvitationRequest
import com.team376.pulsemetry.enrollment.contract.CreateInvitationResponse
import com.team376.pulsemetry.enrollment.secret.AdminAuthenticator
import com.team376.pulsemetry.enrollment.service.InvitationAdminService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 관리자용 초대 API (PLAN.md §6.5).
 *
 * 인증은 정적 키 하나뿐이다 (L8). 헤더는 `required = false` 로 받아
 * 누락도 401 로 처리한다 — 헤더가 없다는 이유로 400 을 주면 인증 실패와 형식 오류가 뒤섞인다.
 */
@RestController
@RequestMapping("/v1/invitations")
class InvitationAdminController(
	private val adminAuthenticator: AdminAuthenticator,
	private val invitationAdminService: InvitationAdminService,
) {

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	fun create(
		@RequestHeader(value = ADMIN_TOKEN_HEADER, required = false) adminToken: String?,
		@RequestBody request: CreateInvitationRequest,
	): CreateInvitationResponse {
		adminAuthenticator.authenticate(adminToken)
		return invitationAdminService.create(request)
	}

	@PostMapping("/{id}/revoke")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun revoke(
		@RequestHeader(value = ADMIN_TOKEN_HEADER, required = false) adminToken: String?,
		@PathVariable id: UUID,
	) {
		adminAuthenticator.authenticate(adminToken)
		invitationAdminService.revoke(id)
	}

	companion object {
		const val ADMIN_TOKEN_HEADER = "X-Admin-Token"
	}
}
