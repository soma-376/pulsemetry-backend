package com.team376.pulsemetry.persistence.enrollment.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** 사용자의 PC 에 설치된 Pulsemetry CLI 또는 daemon. */
@Entity
@Table(name = "installations", schema = "enrollment")
class Installation(

	@Column(name = "tenant_id", nullable = false)
	var tenantId: UUID,

	@Column(name = "member_id", nullable = false)
	var memberId: UUID,

	@Column(name = "invitation_id", nullable = false)
	var invitationId: UUID,

	@Enumerated(EnumType.STRING)
	@Column(name = "platform", nullable = false, length = 20)
	var platform: Platform,

	@Id
	@Column(name = "id", nullable = false)
	var id: UUID = UUID.randomUUID(),

	@Column(name = "hostname", length = 255)
	var hostname: String? = null,

	@Column(name = "architecture", length = 30)
	var architecture: String? = null,

	@Column(name = "client_version", length = 50)
	var clientVersion: String? = null,

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	var status: InstallationStatus = InstallationStatus.active,

	@Column(name = "last_seen_at")
	var lastSeenAt: Instant? = null,

	@Column(name = "revoked_at")
	var revokedAt: Instant? = null,

	@Column(name = "created_at", nullable = false)
	var createdAt: Instant = Instant.now(),

	@Column(name = "updated_at", nullable = false)
	var updatedAt: Instant = Instant.now(),
) {
	fun isActive(): Boolean = status == InstallationStatus.active
}
