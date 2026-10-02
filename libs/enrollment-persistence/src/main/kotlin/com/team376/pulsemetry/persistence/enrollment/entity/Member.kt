package com.team376.pulsemetry.persistence.enrollment.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * 조직 구성원.
 *
 * 관리자 등 웹 사용자는 외부 OIDC 인증 후 자체 서비스 세션 토큰을 사용하고(허브 ADR 0013),
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

	@Column(name = "display_name", length = 100)
	var displayName: String? = null,

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "role", nullable = false, columnDefinition = "member_role")
	var role: MemberRole = MemberRole.member,

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "status", nullable = false, columnDefinition = "member_status")
	var status: MemberStatus = MemberStatus.active,

	@Column(name = "created_at", nullable = false)
	var createdAt: Instant = Instant.now(),

	@Column(name = "updated_at", nullable = false)
	var updatedAt: Instant = Instant.now(),

	/** 외부 신원은 사전 등록한다. NULL이면 설치는 가능하지만 OIDC 로그인은 불가하다. */
	@Column(name = "oidc_subject", length = 255)
	var oidcSubject: String? = null,
) {
	/**
	 * 초대를 발급할 수 있는 권한인지 (PLAN.md §6.5 — owner 또는 admin 만 가능).
	 *
	 * 정지된 계정은 role 이 무엇이든 발급할 수 없다 — 정지의 목적이 권한을 끊는 것이다.
	 */
	fun canInvite(): Boolean =
		status == MemberStatus.active && (role == MemberRole.owner || role == MemberRole.admin)
}
