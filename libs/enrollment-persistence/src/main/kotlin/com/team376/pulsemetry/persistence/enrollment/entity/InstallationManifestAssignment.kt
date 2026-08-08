package com.team376.pulsemetry.persistence.enrollment.entity

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.util.UUID

/** (installation_id, manifest_id) 복합 PK. */
@Embeddable
data class InstallationManifestAssignmentId(

	@Column(name = "installation_id", nullable = false)
	var installationId: UUID,

	@Column(name = "manifest_id", nullable = false)
	var manifestId: UUID,
) : Serializable

/**
 * installation 에 배포된 manifest 버전과 적용 여부.
 *
 * enroll 시점에는 [appliedAt] 이 NULL 이다 — 클라이언트가 실제로 적용했다는 보고는 아직 없다 (PLAN.md §6.2 9단계).
 */
@Entity
@Table(name = "installation_manifest_assignments", schema = "enrollment")
class InstallationManifestAssignment(

	@EmbeddedId
	var id: InstallationManifestAssignmentId,

	@Column(name = "assigned_at", nullable = false)
	var assignedAt: Instant = Instant.now(),

	@Column(name = "applied_at")
	var appliedAt: Instant? = null,
) {
	constructor(installationId: UUID, manifestId: UUID) :
		this(InstallationManifestAssignmentId(installationId, manifestId))

	val installationId: UUID get() = id.installationId

	val manifestId: UUID get() = id.manifestId
}
