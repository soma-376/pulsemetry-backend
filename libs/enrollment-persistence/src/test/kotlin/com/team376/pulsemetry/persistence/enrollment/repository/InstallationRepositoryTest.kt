package com.team376.pulsemetry.persistence.enrollment.repository

import com.team376.pulsemetry.persistence.enrollment.entity.InstallationStatus
import com.team376.pulsemetry.persistence.enrollment.support.AbstractPersistenceIntegrationTest
import com.team376.pulsemetry.persistence.enrollment.support.EnrollmentFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * 관측 보강이 `member_id` 를 얻는 installation 단독 조회를 본다(ADR 0020 §5). 소속과 상태에 무관하게
 * installation 에 귀속된 구성원을 돌려줘야 한다.
 */
@Transactional
class InstallationRepositoryTest : AbstractPersistenceIntegrationTest() {

	@Autowired
	private lateinit var tenants: TenantRepository

	@Autowired
	private lateinit var members: MemberRepository

	@Autowired
	private lateinit var invitations: InvitationRepository

	@Autowired
	private lateinit var installations: InstallationRepository

	private lateinit var tenantId: UUID
	private lateinit var memberId: UUID
	private lateinit var invitationId: UUID

	@BeforeEach
	fun setUp() {
		tenantId = tenants.saveAndFlush(EnrollmentFixtures.tenant()).id
		memberId = members.saveAndFlush(EnrollmentFixtures.member(tenantId)).id
		invitationId = invitations.saveAndFlush(EnrollmentFixtures.invitation(tenantId, memberId)).id
	}

	@Test
	@DisplayName("installation 의 구성원을 돌려준다 — 소속이 없어도 된다")
	fun findsMemberOfInstallation() {
		val installationId = installations
			.saveAndFlush(EnrollmentFixtures.installation(tenantId, memberId, invitationId))
			.id

		assertThat(installations.findMemberIdById(installationId)).isEqualTo(memberId)
	}

	@Test
	@DisplayName("revoked installation 도 구성원을 돌려준다 — 과거 사용량은 그 사람의 것이다")
	fun revokedInstallationStillResolvesMember() {
		val revoked = EnrollmentFixtures.installation(tenantId, memberId, invitationId).apply {
			status = InstallationStatus.revoked
			revokedAt = Instant.parse("2026-06-01T00:00:00Z")
		}
		val installationId = installations.saveAndFlush(revoked).id

		assertThat(installations.findMemberIdById(installationId)).isEqualTo(memberId)
	}

	@Test
	@DisplayName("없는 installation 은 null 이다")
	fun unknownInstallationIsNull() {
		assertThat(installations.findMemberIdById(UUID.randomUUID())).isNull()
	}

	// ── 여러 ID 조회(ADR 0084) ─────────────────────────────────────────────

	@Test
	@DisplayName("여러 installation 을 한 번에 읽으면 ID 마다의 단건 조회와 같다 — revoked 도 오고, 없는 ID 는 빠진다")
	fun findsMembersOfSeveralInstallations() {
		val otherMemberId = members.saveAndFlush(EnrollmentFixtures.member(tenantId)).id
		val otherInvitationId = invitations.saveAndFlush(EnrollmentFixtures.invitation(tenantId, otherMemberId)).id
		val active = installations.saveAndFlush(EnrollmentFixtures.installation(tenantId, memberId, invitationId)).id
		val revoked = installations.saveAndFlush(
			EnrollmentFixtures.installation(tenantId, memberId, invitationId).apply {
				status = InstallationStatus.revoked
				revokedAt = Instant.parse("2026-06-01T00:00:00Z")
			},
		).id
		val other = installations.saveAndFlush(EnrollmentFixtures.installation(tenantId, otherMemberId, otherInvitationId)).id
		val unknown = UUID.randomUUID()

		val rows = installations.findMemberIdsByIds(listOf(active, revoked, other, unknown))

		assertThat(rows.map { it.installationId }).containsExactlyInAnyOrder(active, revoked, other)
		assertThat(rows.associate { it.installationId to it.memberId })
			.isEqualTo(listOf(active, revoked, other).associateWith { installations.findMemberIdById(it) })
			.containsEntry(other, otherMemberId)
	}

	@Test
	@DisplayName("없는 ID 만 넘기면 빈 목록이다")
	fun unknownInstallationsAreEmpty() {
		assertThat(installations.findMemberIdsByIds(listOf(UUID.randomUUID(), UUID.randomUUID()))).isEmpty()
	}
}
