package com.team376.pulsemetry.persistence.enrollment.repository

import com.team376.pulsemetry.persistence.enrollment.entity.Installation
import com.team376.pulsemetry.persistence.enrollment.entity.InstallationCredential
import com.team376.pulsemetry.persistence.enrollment.entity.InstallationManifestAssignment
import com.team376.pulsemetry.persistence.enrollment.entity.InstallationManifestAssignmentId
import com.team376.pulsemetry.persistence.enrollment.entity.Manifest
import com.team376.pulsemetry.persistence.enrollment.entity.Member
import com.team376.pulsemetry.persistence.enrollment.entity.Tenant
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface TenantRepository : JpaRepository<Tenant, UUID>

interface MemberRepository : JpaRepository<Member, UUID> {

	/** 초대 발급 시 기존 구성원을 찾는다. 없으면 호출자가 `invited` 상태로 새로 만든다 (PLAN.md §6.5). */
	fun findByTenantIdAndEmail(tenantId: UUID, email: String): Member?

	/**
	 * `invited` 구성원을 `active` 로 전환한다. 설치 완료(enroll, 또는 pit_ 재발급)가 전환 이벤트다 —
	 * OTLP 경로의 auth-proxy 가 `invited` 를 거부하므로, 이 전환 없이는 발급된 토큰이 전부 401 이 된다.
	 *
	 * WHERE 가 `invited` 만 잡으므로 `active` 는 no-op 이고 **`suspended` 는 절대 건드리지 않는다** —
	 * 정지 해제는 관리자의 결정이지 설치의 부수효과가 아니다.
	 *
	 * @return 영향 행 수. 1이면 전환됨. 0이면 이미 active 이거나 suspended 이며, 호출자는 분기하지 않는다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(
		"""
		UPDATE Member m
		SET m.status = com.team376.pulsemetry.persistence.enrollment.entity.MemberStatus.active,
			m.updatedAt = :now
		WHERE m.id = :id
		  AND m.status = com.team376.pulsemetry.persistence.enrollment.entity.MemberStatus.invited
		""",
	)
	fun activateInvited(
		@Param("id") id: UUID,
		@Param("now") now: Instant,
	): Int
}

interface InstallationRepository : JpaRepository<Installation, UUID>

interface InstallationCredentialRepository : JpaRepository<InstallationCredential, UUID> {

	/**
	 * `Authorization: Bearer <installation_token>` 의 SHA-256 으로 자격증명을 찾는다.
	 * 해시가 결정론적이어야 이 조회가 성립한다 — bcrypt·Argon2 를 쓸 수 없는 이유다 (PLAN.md L11).
	 */
	fun findByCredentialHash(credentialHash: String): InstallationCredential?

	/**
	 * 자격증명이 마지막으로 쓰인 시각을 남긴다 (PLAN.md §6.3).
	 *
	 * 엔티티 setter 대신 UPDATE 문인 이유는, 같은 트랜잭션에서 도는
	 * [TelemetryTokenRepository.revokeActiveByInstallationId] 가 영속성 컨텍스트를 비우기 때문이다.
	 * 비워진 뒤에는 detach 된 엔티티의 변경이 flush 되지 않는다 — 순서에 의존하지 않도록 문장으로 만든다.
	 *
	 * @return 영향 행 수. 1이 아니면 자격증명이 사라진 것이다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(
		"""
		UPDATE InstallationCredential c
		SET c.lastUsedAt = :now
		WHERE c.id = :id
		""",
	)
	fun touchLastUsedAt(
		@Param("id") id: UUID,
		@Param("now") now: Instant,
	): Int
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
