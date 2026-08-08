package com.team376.pulsemetry.enrollment.contract

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.util.UUID

/**
 * `POST /v1/invitations` 요청 (PLAN.md §6.5).
 *
 * telemetryctl 계약이 아니라 서버가 정의하는 관리자용 계약이다.
 * 필수 필드가 없으면 Jackson 이 역직렬화에서 실패하고 400 `invalid_request` 가 된다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class CreateInvitationRequest(

	@JsonProperty("tenant_id")
	val tenantId: UUID,

	@JsonProperty("created_by_member_id")
	val createdByMemberId: UUID,

	@JsonProperty("email")
	val email: String,

	@JsonProperty("display_name")
	val displayName: String? = null,

	/** 생략하면 `pulsemetry.invitation.default-ttl-hours` (기본 72). */
	@JsonProperty("expires_in_hours")
	val expiresInHours: Long? = null,
)

/**
 * `POST /v1/invitations` 응답 (201).
 *
 * **원본 [code] 는 여기서 딱 한 번만 나간다.** DB 에는 해시만 있으므로 다시 볼 방법이 없고,
 * 그래서 재조회 API 를 만들지 않는다 (PLAN.md R4). 관리자가 이 응답을 잃으면 새로 발급해야 한다.
 */
data class CreateInvitationResponse(

	@JsonProperty("invitation_id")
	val invitationId: UUID,

	@JsonProperty("code")
	val code: String,

	@JsonProperty("expires_at")
	val expiresAt: Instant,

	@JsonProperty("install_commands")
	val installCommands: InstallCommands,
)

/** 사용자가 터미널에 그대로 붙여넣는 한 줄 설치 명령. */
data class InstallCommands(

	@JsonProperty("windows")
	val windows: String,

	@JsonProperty("unix")
	val unix: String,
)
