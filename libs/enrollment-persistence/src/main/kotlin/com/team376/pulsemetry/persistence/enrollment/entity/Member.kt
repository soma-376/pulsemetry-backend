package com.team376.pulsemetry.persistence.enrollment.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * 조직 구성원.
 *
 * 관리자 등 웹 사용자는 Cognito 계정(`cognitoUserSub`)과 연결되지만,
 * 초대로 들어온 일반 사용자는 `status='invited'` 로만 만들어지고 installation 으로 서비스와 이어진다.
 */
@Entity
@Table(name = "members", schema = "enrollment")
class Member(

	@Column(name = "tenant_id", nullable = false)
	var tenantId: UUID,

	@Column(name = "email", nullable = false, length = 320)
	var email: String,

	@Id
	@Column(name = "id", nullable = false)
	var id: UUID = UUID.randomUUID(),

	@Column(name = "cognito_user_sub", length = 255)
	var cognitoUserSub: String? = null,

	@Column(name = "display_name", length = 100)
	var displayName: String? = null,

	@Enumerated(EnumType.STRING)
	@Column(name = "role", nullable = false, length = 20)
	var role: MemberRole = MemberRole.member,

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	var status: MemberStatus = MemberStatus.active,

	@Column(name = "created_at", nullable = false)
	var createdAt: Instant = Instant.now(),

	@Column(name = "updated_at", nullable = false)
	var updatedAt: Instant = Instant.now(),
) {
	/** 초대를 발급할 수 있는 권한인지 (PLAN.md §6.5 — owner 또는 admin 만 가능). */
	fun canInvite(): Boolean = role == MemberRole.owner || role == MemberRole.admin
}
