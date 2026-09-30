package com.team376.pulsemetry.persistence.enrollment.repository

import com.team376.pulsemetry.persistence.enrollment.entity.Installation
import com.team376.pulsemetry.persistence.enrollment.entity.InstallationCredential
import com.team376.pulsemetry.persistence.enrollment.entity.InstallationManifestAssignment
import com.team376.pulsemetry.persistence.enrollment.entity.InstallationManifestAssignmentId
import com.team376.pulsemetry.persistence.enrollment.entity.Manifest
import com.team376.pulsemetry.persistence.enrollment.entity.Member
import com.team376.pulsemetry.persistence.enrollment.entity.Team
import com.team376.pulsemetry.persistence.enrollment.entity.TeamMembership
import com.team376.pulsemetry.persistence.enrollment.entity.Tenant
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
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
	 * native query 인 이유: JPQL 의 enum 리터럴을 Hibernate 가 `cast(? as memberstatus)` 로
	 * 렌더링하는데, 그 타입명은 Java enum 단순명에서 유도된 것이라 실제 DB 타입
	 * `member_status`(ADR 0009)와 어긋나 42704 로 죽는다. native SQL 의 문자열 리터럴은
	 * enum 컬럼에 암묵 캐스트되므로 타입명 유도에 의존하지 않는다.
	 *
	 * @return 영향 행 수. 1이면 전환됨. 0이면 이미 active 이거나 suspended 이며, 호출자는 분기하지 않는다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(
		nativeQuery = true,
		value = """
		UPDATE enrollment.members
		SET status = 'active', updated_at = :now
		WHERE id = :id
		  AND status = 'invited'
		""",
	)
	fun activateInvited(
		@Param("id") id: UUID,
		@Param("now") now: Instant,
	): Int
}

interface TeamRepository : JpaRepository<Team, UUID> {

	fun findAllByTenantId(tenantId: UUID): List<Team>
}

interface TeamMembershipRepository : JpaRepository<TeamMembership, UUID> {

	/**
	 * 구성원의 소속 이력 전부. **팀 상태로 거르지 않는다** — archived 팀의 과거 소속도 온다.
	 *
	 * 관측 보강(ADR 0020 §5)의 as-of 조회가 이것을 쓴다. 이벤트 시점의 소속만 보고 팀의 현재 상태는 보지 않는다.
	 *
	 * 시점 필터(`joined_at <= at < left_at`)를 SQL 에 넣지 않는다 — 보강은 push 하나에 담긴 여러 관측을 서로 다른
	 * `source_time` 으로 판정하므로, 구성원당 한 번만 읽어 두고 각 관측을 [TeamMembership.coversAt] 으로 거른다.
	 */
	fun findAllByMemberId(memberId: UUID): List<TeamMembership>
}

interface InstallationRepository : JpaRepository<Installation, UUID> {

	/**
	 * installation 행을 `SELECT … FOR UPDATE` 로 잠가서 가져온다.
	 *
	 * telemetry token 재발급이 이걸로 시작해야 하는 이유: READ COMMITTED 에서 동시 재발급 두
	 * 트랜잭션의 [TelemetryTokenRepository.revokeActiveByInstallationId] 는 서로의 미커밋
	 * INSERT 를 보지 못해 활성 토큰이 2개 남는다 — "재발급 = 이전 토큰 전면 무효화"(PLAN.md §6.3)
	 * 계약 파괴다. 이 잠금이 재발급을 installation 단위로 직렬화하고, 부분 유니크 인덱스
	 * `ux_telemetry_tokens_installation_active`(V3)가 최종 방어선이 된다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	fun findWithLockById(id: UUID): Installation?

	/**
	 * installation 이 귀속된 구성원 ID 만 읽는다. 없으면 null 이다.
	 *
	 * **상태를 보지 않는다** — revoked installation 도 구성원을 돌려준다. 과거 사용량은 그 사람의 것이다
	 * (ADR 0020 §5). 소속 조회와 독립이라 무소속 구성원도 식별된다. 잠그지 않는 읽기 전용 조회다.
	 */
	@Query("SELECT i.memberId FROM Installation i WHERE i.id = :id")
	fun findMemberIdById(@Param("id") id: UUID): UUID?

	/**
	 * 설치가 보고한 생존 시각과 데몬 버전을 남긴다 (ADR 0040). `updated_at` 은 건드리지 않는다 — 주기 보고는 설치의 변경이 아니다.
	 *
	 * 엔티티 setter 대신 UPDATE 문이다. 같은 트랜잭션의 다른 수정 쿼리가 영속성 컨텍스트를 비워도 순서에 의존하지 않는다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("UPDATE Installation i SET i.lastSeenAt = :now, i.clientVersion = :version WHERE i.id = :id")
	fun recordSeen(@Param("id") id: UUID, @Param("now") now: Instant, @Param("version") version: String): Int
}

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

	/**
	 * 설치가 그 manifest 를 적용했다고 보고했다 (ADR 0040). 배정 행이 없으면 만들고, `applied_at` 이 비어 있을 때만 채운다 —
	 * 적용 확인 시각은 그 판을 **처음** 보고받은 시각이고 같은 보고를 다시 받아도 바뀌지 않는다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(
		nativeQuery = true,
		value = """
		INSERT INTO enrollment.installation_manifest_assignments (installation_id, manifest_id, assigned_at, applied_at)
		VALUES (:installationId, :manifestId, :now, :now)
		ON CONFLICT (installation_id, manifest_id)
		DO UPDATE SET applied_at = COALESCE(enrollment.installation_manifest_assignments.applied_at, EXCLUDED.applied_at)
		""",
	)
	fun acknowledge(
		@Param("installationId") installationId: UUID,
		@Param("manifestId") manifestId: UUID,
		@Param("now") now: Instant,
	): Int
}
