package com.team376.pulsemetry.persistence.enrollment.repository

import com.team376.pulsemetry.persistence.enrollment.entity.Installation
import com.team376.pulsemetry.persistence.enrollment.entity.InstallationCredential
import com.team376.pulsemetry.persistence.enrollment.entity.InstallationManifestAssignment
import com.team376.pulsemetry.persistence.enrollment.entity.InstallationManifestAssignmentId
import com.team376.pulsemetry.persistence.enrollment.entity.Manifest
import com.team376.pulsemetry.persistence.enrollment.entity.Member
import com.team376.pulsemetry.persistence.enrollment.entity.Tenant
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TenantRepository : JpaRepository<Tenant, UUID>

interface MemberRepository : JpaRepository<Member, UUID> {

	/** 초대 발급 시 기존 구성원을 찾는다. 없으면 호출자가 `invited` 상태로 새로 만든다 (PLAN.md §6.5). */
	fun findByTenantIdAndEmail(tenantId: UUID, email: String): Member?
}

interface InstallationRepository : JpaRepository<Installation, UUID>

interface InstallationCredentialRepository : JpaRepository<InstallationCredential, UUID> {

	/**
	 * `Authorization: Bearer <installation_token>` 의 SHA-256 으로 자격증명을 찾는다.
	 * 해시가 결정론적이어야 이 조회가 성립한다 — bcrypt·Argon2 를 쓸 수 없는 이유다 (PLAN.md L11).
	 */
	fun findByCredentialHash(credentialHash: String): InstallationCredential?
}

interface ManifestRepository : JpaRepository<Manifest, UUID> {

	/**
	 * tenant 의 활성 manifest. 부분 유니크 인덱스가 있으므로 최대 한 건이다.
	 * 없으면 enroll 은 409 `manifest_not_configured` 로 실패한다 (PLAN.md §6.2 8단계).
	 */
	fun findByTenantIdAndIsActiveTrue(tenantId: UUID): Manifest?

	fun findByTenantIdAndVersion(tenantId: UUID, version: Int): Manifest?
}

interface InstallationManifestAssignmentRepository :
	JpaRepository<InstallationManifestAssignment, InstallationManifestAssignmentId> {

	fun findAllByIdInstallationId(installationId: UUID): List<InstallationManifestAssignment>
}
