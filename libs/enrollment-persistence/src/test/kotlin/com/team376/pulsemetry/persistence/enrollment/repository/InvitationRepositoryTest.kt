package com.team376.pulsemetry.persistence.enrollment.repository

import com.team376.pulsemetry.persistence.enrollment.support.AbstractPersistenceIntegrationTest
import com.team376.pulsemetry.persistence.enrollment.support.EnrollmentFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 초대 소비·폐기는 조건부 UPDATE 한 방이어야 한다 (PLAN.md A6).
 * 그래서 이 테스트의 관심사는 반환된 **영향 행 수**다. 0 과 1 의 구분이 곧 HTTP 상태코드가 된다.
 */
@Transactional
class InvitationRepositoryTest : AbstractPersistenceIntegrationTest() {

	@Autowired
	private lateinit var invitations: InvitationRepository

	@Autowired
	private lateinit var tenants: TenantRepository

	@Autowired
	private lateinit var members: MemberRepository

	private lateinit var tenantId: UUID
	private lateinit var memberId: UUID

	@BeforeEach
	fun setUp() {
		tenantId = tenants.saveAndFlush(EnrollmentFixtures.tenant()).id
		memberId = members.saveAndFlush(EnrollmentFixtures.member(tenantId)).id
	}

	// ── 소비 ─────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("유효한 초대를 소비하면 1행이 영향받고 used_at 이 채워진다")
	fun consumeValidInvitation() {
		val codeHash = EnrollmentFixtures.randomHash()
		val id = invitations.saveAndFlush(
			EnrollmentFixtures.invitation(tenantId, memberId, codeHash = codeHash),
		).id

		val affected = invitations.consume(codeHash, Instant.now())

		assertThat(affected).isEqualTo(1)
		assertThat(invitations.findById(id).orElseThrow().usedAt).isNotNull()
	}

	@Test
	@DisplayName("이미 소비된 초대를 다시 소비하면 0행이다 — 코드는 일회용이다")
	fun consumeAlreadyUsedInvitation() {
		val codeHash = EnrollmentFixtures.randomHash()
		invitations.saveAndFlush(EnrollmentFixtures.invitation(tenantId, memberId, codeHash = codeHash))

		val first = invitations.consume(codeHash, Instant.now())
		val second = invitations.consume(codeHash, Instant.now())

		assertThat(first).isEqualTo(1)
		assertThat(second).isEqualTo(0)
	}

	@Test
	@DisplayName("폐기된 초대는 소비되지 않는다 — 0행")
	fun consumeRevokedInvitation() {
		val codeHash = EnrollmentFixtures.randomHash()
		invitations.saveAndFlush(
			EnrollmentFixtures.invitation(
				tenantId,
				memberId,
				codeHash = codeHash,
				revokedAt = Instant.now().minus(1, ChronoUnit.HOURS),
			),
		)

		assertThat(invitations.consume(codeHash, Instant.now())).isEqualTo(0)
	}

	@Test
	@DisplayName("만료된 초대는 소비되지 않는다 — 0행")
	fun consumeExpiredInvitation() {
		val codeHash = EnrollmentFixtures.randomHash()
		invitations.saveAndFlush(
			EnrollmentFixtures.invitation(
				tenantId,
				memberId,
				codeHash = codeHash,
				expiresAt = Instant.now().minus(1, ChronoUnit.SECONDS),
			),
		)

		assertThat(invitations.consume(codeHash, Instant.now())).isEqualTo(0)
	}

	@Test
	@DisplayName("만료 경계: expires_at 이 지금과 같으면 소비되지 않는다")
	fun consumeAtExactExpiry() {
		val codeHash = EnrollmentFixtures.randomHash()
		val expiresAt = Instant.now().truncatedTo(ChronoUnit.MILLIS)
		invitations.saveAndFlush(
			EnrollmentFixtures.invitation(tenantId, memberId, codeHash = codeHash, expiresAt = expiresAt),
		)

		assertThat(invitations.consume(codeHash, expiresAt)).isEqualTo(0)
	}

	@Test
	@DisplayName("존재하지 않는 코드 해시를 소비하면 0행이다")
	fun consumeUnknownCodeHash() {
		assertThat(invitations.consume(EnrollmentFixtures.randomHash(), Instant.now())).isEqualTo(0)
	}

	@Test
	@DisplayName("소비는 해당 코드 한 건에만 영향을 준다")
	fun consumeTouchesOnlyMatchingRow() {
		val targetHash = EnrollmentFixtures.randomHash()
		invitations.saveAndFlush(EnrollmentFixtures.invitation(tenantId, memberId, codeHash = targetHash))
		val otherId = invitations.saveAndFlush(
			EnrollmentFixtures.invitation(tenantId, memberId, codeHash = EnrollmentFixtures.randomHash()),
		).id

		assertThat(invitations.consume(targetHash, Instant.now())).isEqualTo(1)
		assertThat(invitations.findById(otherId).orElseThrow().usedAt).isNull()
	}

	// ── 폐기 ─────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("미사용 초대를 폐기하면 1행이 영향받고 revoked_at 이 채워진다")
	fun revokeUnusedInvitation() {
		val id = invitations.saveAndFlush(EnrollmentFixtures.invitation(tenantId, memberId)).id

		val affected = invitations.revoke(id, Instant.now())

		assertThat(affected).isEqualTo(1)
		assertThat(invitations.findById(id).orElseThrow().revokedAt).isNotNull()
	}

	@Test
	@DisplayName("이미 사용된 초대는 폐기되지 않는다 — 0행 (409 invitation_used)")
	fun revokeUsedInvitation() {
		val id = invitations.saveAndFlush(
			EnrollmentFixtures.invitation(tenantId, memberId, usedAt = Instant.now()),
		).id

		assertThat(invitations.revoke(id, Instant.now())).isEqualTo(0)
	}

	@Test
	@DisplayName("이미 폐기된 초대를 다시 폐기하면 0행이다 — 409 invitation_revoked")
	fun revokeAlreadyRevokedInvitation() {
		val id = invitations.saveAndFlush(
			EnrollmentFixtures.invitation(tenantId, memberId, revokedAt = Instant.now()),
		).id

		assertThat(invitations.revoke(id, Instant.now())).isEqualTo(0)
	}

	@Test
	@DisplayName("존재하지 않는 id 를 폐기하면 0행이다 — 404 invitation_not_found")
	fun revokeUnknownInvitation() {
		assertThat(invitations.revoke(UUID.randomUUID(), Instant.now())).isEqualTo(0)
	}

	// ── 실패 사유 판별 ───────────────────────────────────────────────────────

	@Test
	@DisplayName("소비 실패 후 findByCodeHash 로 사유를 판별할 수 있다")
	fun findByCodeHashTellsFailureReason() {
		val codeHash = EnrollmentFixtures.randomHash()
		invitations.saveAndFlush(EnrollmentFixtures.invitation(tenantId, memberId, codeHash = codeHash))
		invitations.consume(codeHash, Instant.now())

		val found = invitations.findByCodeHash(codeHash)

		assertThat(found).isNotNull()
		assertThat(found!!.isUsed()).isTrue()
		assertThat(found.isRevoked()).isFalse()
	}

	@Test
	@DisplayName("없는 코드 해시는 findByCodeHash 가 null 을 준다 — 404 로 이어진다")
	fun findByCodeHashReturnsNullForUnknown() {
		assertThat(invitations.findByCodeHash(EnrollmentFixtures.randomHash())).isNull()
	}
}
