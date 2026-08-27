package com.team376.pulsemetry.persistence.enrollment.repository

import com.team376.pulsemetry.persistence.enrollment.entity.InstallationManifestAssignment
import com.team376.pulsemetry.persistence.enrollment.entity.InstallationManifestAssignmentId
import com.team376.pulsemetry.persistence.enrollment.entity.MemberRole
import com.team376.pulsemetry.persistence.enrollment.entity.MemberStatus
import com.team376.pulsemetry.persistence.enrollment.entity.Platform
import com.team376.pulsemetry.persistence.enrollment.support.AbstractPersistenceIntegrationTest
import com.team376.pulsemetry.persistence.enrollment.support.EnrollmentFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 엔티티 매핑이 DDL 과 실제로 맞물리는지 본다.
 *
 * `@Enumerated(STRING)` 은 상수 이름을 그대로 저장하므로, 상수가 대문자면 CHECK 제약에 걸린다.
 * 그래서 저장된 **원시 문자열**을 JDBC 로 직접 읽어 확인한다.
 */
@Transactional
class EnrollmentEntityMappingTest : AbstractPersistenceIntegrationTest() {

	@Autowired
	private lateinit var tenants: TenantRepository

	@Autowired
	private lateinit var members: MemberRepository

	@Autowired
	private lateinit var invitations: InvitationRepository

	@Autowired
	private lateinit var installations: InstallationRepository

	@Autowired
	private lateinit var credentials: InstallationCredentialRepository

	@Autowired
	private lateinit var manifests: ManifestRepository

	@Autowired
	private lateinit var assignments: InstallationManifestAssignmentRepository

	@Autowired
	private lateinit var jdbcClient: JdbcClient

	private lateinit var tenantId: UUID
	private lateinit var memberId: UUID

	@BeforeEach
	fun setUp() {
		tenantId = tenants.saveAndFlush(EnrollmentFixtures.tenant()).id
		memberId = members.saveAndFlush(EnrollmentFixtures.member(tenantId)).id
	}

	private fun newInstallation(platform: Platform = Platform.macos): UUID {
		val invitationId = invitations.saveAndFlush(EnrollmentFixtures.invitation(tenantId, memberId)).id
		return installations.saveAndFlush(
			EnrollmentFixtures.installation(tenantId, memberId, invitationId, platform = platform),
		).id
	}

	// ── enum 매핑 ────────────────────────────────────────────────────────────

	@Test
	@DisplayName("Platform 이 소문자 그대로 저장된다 — CHECK 제약과 맞물린다")
	fun platformIsStoredLowercase() {
		val installationId = newInstallation(Platform.macos)

		val stored = jdbcClient
			.sql("SELECT platform FROM enrollment.installations WHERE id = :id")
			.param("id", installationId)
			.query(String::class.java)
			.single()

		assertThat(stored).isEqualTo("macos")
	}

	@Test
	@DisplayName("세 platform 값이 모두 왕복한다")
	fun allPlatformsRoundTrip() {
		Platform.entries.forEach { platform ->
			val id = newInstallation(platform)
			assertThat(installations.findById(id).orElseThrow().platform).isEqualTo(platform)
		}
	}

	@Test
	@DisplayName("member 의 role·status 도 소문자로 저장된다")
	fun memberEnumsAreStoredLowercase() {
		val id = members.saveAndFlush(
			EnrollmentFixtures.member(tenantId, role = MemberRole.admin),
		).id

		val row = jdbcClient
			.sql("SELECT role || ',' || status FROM enrollment.members WHERE id = :id")
			.param("id", id)
			.query(String::class.java)
			.single()

		assertThat(row).isEqualTo("admin,active")
	}

	@Test
	@DisplayName("초대로 생긴 member 는 invited 상태로 저장된다")
	fun invitedMemberStatus() {
		val member = EnrollmentFixtures.member(tenantId, role = MemberRole.member)
		member.status = MemberStatus.invited
		val id = members.saveAndFlush(member).id

		assertThat(members.findById(id).orElseThrow().status).isEqualTo(MemberStatus.invited)
	}

	@Test
	@DisplayName("활성 owner·admin 만 초대를 발급할 수 있다")
	fun onlyOwnerAndAdminCanInvite() {
		assertThat(EnrollmentFixtures.member(tenantId, role = MemberRole.owner).canInvite()).isTrue()
		assertThat(EnrollmentFixtures.member(tenantId, role = MemberRole.admin).canInvite()).isTrue()
		assertThat(EnrollmentFixtures.member(tenantId, role = MemberRole.member).canInvite()).isFalse()
	}

	@Test
	@DisplayName("정지된 owner·admin 은 초대를 발급할 수 없다 — role 만으로 판단하지 않는다")
	fun suspendedAdminsCannotInvite() {
		listOf(MemberRole.owner, MemberRole.admin).forEach { role ->
			val suspended = EnrollmentFixtures.member(tenantId, role = role, status = MemberStatus.suspended)

			assertThat(suspended.canInvite())
				.describedAs("정지된 %s", role)
				.isFalse()
		}
	}

	@Test
	@DisplayName("아직 설치하지 않은 invited owner·admin 도 발급할 수 없다")
	fun invitedAdminsCannotInvite() {
		val invited = EnrollmentFixtures.member(tenantId, role = MemberRole.owner, status = MemberStatus.invited)

		assertThat(invited.canInvite()).isFalse()
	}

	// ── 조회 ─────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("tenant + email 로 member 를 찾는다")
	fun findMemberByTenantAndEmail() {
		members.saveAndFlush(EnrollmentFixtures.member(tenantId, email = "hong@example.com"))

		assertThat(members.findByTenantIdAndEmail(tenantId, "hong@example.com")).isNotNull()
		assertThat(members.findByTenantIdAndEmail(tenantId, "nobody@example.com")).isNull()
	}

	@Test
	@DisplayName("다른 tenant 의 같은 email 은 찾히지 않는다")
	fun memberLookupIsScopedToTenant() {
		val otherTenantId = tenants.saveAndFlush(EnrollmentFixtures.tenant("다른 조직")).id
		members.saveAndFlush(EnrollmentFixtures.member(otherTenantId, email = "shared@example.com"))

		assertThat(members.findByTenantIdAndEmail(tenantId, "shared@example.com")).isNull()
		assertThat(members.findByTenantIdAndEmail(otherTenantId, "shared@example.com")).isNotNull()
	}

	@Test
	@DisplayName("credential 해시로 자격증명을 찾는다 — 결정론적 해시여야 성립한다")
	fun findCredentialByHash() {
		val installationId = newInstallation()
		val hash = EnrollmentFixtures.randomHash()
		credentials.saveAndFlush(EnrollmentFixtures.credential(installationId, hash))

		val found = credentials.findByCredentialHash(hash)

		assertThat(found).isNotNull()
		assertThat(found!!.installationId).isEqualTo(installationId)
		assertThat(found.isRevoked()).isFalse()
	}

	// ── manifest ─────────────────────────────────────────────────────────────

	@Test
	@DisplayName("활성 manifest 를 tenant 로 찾는다")
	fun findActiveManifest() {
		manifests.saveAndFlush(EnrollmentFixtures.manifest(tenantId, memberId, version = 1, isActive = false))
		val activeId = manifests.saveAndFlush(
			EnrollmentFixtures.manifest(tenantId, memberId, version = 2, isActive = true),
		).id

		val found = manifests.findByTenantIdAndIsActiveTrue(tenantId)

		assertThat(found).isNotNull()
		assertThat(found!!.id).isEqualTo(activeId)
		assertThat(found.version).isEqualTo(2)
	}

	@Test
	@DisplayName("활성 manifest 가 없으면 null — enroll 은 409 manifest_not_configured 로 간다")
	fun noActiveManifest() {
		manifests.saveAndFlush(EnrollmentFixtures.manifest(tenantId, memberId, version = 1, isActive = false))

		assertThat(manifests.findByTenantIdAndIsActiveTrue(tenantId)).isNull()
	}

	@Test
	@DisplayName("jsonb 컬럼이 JSON 으로 왕복하고 DB 쪽에서도 JSON 으로 읽힌다")
	fun manifestJsonbRoundTrip() {
		val id = manifests.saveAndFlush(EnrollmentFixtures.manifest(tenantId, memberId)).id

		val protocol = jdbcClient
			.sql("SELECT manifest -> 'otlp' ->> 'protocol' FROM enrollment.manifests WHERE id = :id")
			.param("id", id)
			.query(String::class.java)
			.single()

		assertThat(protocol).isEqualTo("http/protobuf")
		assertThat(manifests.findById(id).orElseThrow().manifest).contains("\"schema_version\"")
	}

	// ── 복합 PK ──────────────────────────────────────────────────────────────

	@Test
	@DisplayName("assignment 는 복합 PK 로 저장되고 applied_at 은 NULL 로 남는다")
	fun assignmentCompositeKey() {
		val installationId = newInstallation()
		val manifestId = manifests.saveAndFlush(EnrollmentFixtures.manifest(tenantId, memberId)).id

		assignments.saveAndFlush(InstallationManifestAssignment(installationId, manifestId))

		val found = assignments
			.findById(InstallationManifestAssignmentId(installationId, manifestId))
			.orElseThrow()

		assertThat(found.installationId).isEqualTo(installationId)
		assertThat(found.manifestId).isEqualTo(manifestId)
		assertThat(found.appliedAt).isNull()
	}

	@Test
	@DisplayName("installation 으로 배포 이력을 조회한다")
	fun findAssignmentsByInstallation() {
		val installationId = newInstallation()
		val v1 = manifests.saveAndFlush(
			EnrollmentFixtures.manifest(tenantId, memberId, version = 1, isActive = false),
		).id
		val v2 = manifests.saveAndFlush(
			EnrollmentFixtures.manifest(tenantId, memberId, version = 2, isActive = true),
		).id
		assignments.saveAndFlush(InstallationManifestAssignment(installationId, v1))
		assignments.saveAndFlush(InstallationManifestAssignment(installationId, v2))

		assertThat(assignments.findAllByIdInstallationId(installationId)).hasSize(2)
	}
}
