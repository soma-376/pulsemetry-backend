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
import java.util.UUID

/**
 * telemetry token 재발급은 "기존 활성 토큰 전부 폐기 → 새로 발급" 이다 (PLAN.md §6.3).
 * 일괄 폐기가 정확히 몇 행을 건드리는지가 관심사다.
 */
@Transactional
class TelemetryTokenRepositoryTest : AbstractPersistenceIntegrationTest() {

	@Autowired
	private lateinit var telemetryTokens: TelemetryTokenRepository

	@Autowired
	private lateinit var installations: InstallationRepository

	@Autowired
	private lateinit var invitations: InvitationRepository

	@Autowired
	private lateinit var tenants: TenantRepository

	@Autowired
	private lateinit var members: MemberRepository

	private lateinit var tenantId: UUID
	private lateinit var memberId: UUID
	private lateinit var installationId: UUID

	@BeforeEach
	fun setUp() {
		tenantId = tenants.saveAndFlush(EnrollmentFixtures.tenant()).id
		memberId = members.saveAndFlush(EnrollmentFixtures.member(tenantId)).id
		installationId = newInstallation()
	}

	private fun newInstallation(): UUID {
		val invitationId = invitations.saveAndFlush(EnrollmentFixtures.invitation(tenantId, memberId)).id
		return installations.saveAndFlush(
			EnrollmentFixtures.installation(tenantId, memberId, invitationId),
		).id
	}

	@Test
	@DisplayName("활성 토큰 3개를 폐기하면 3행이 영향받는다")
	fun revokeAllActiveTokens() {
		repeat(3) { telemetryTokens.saveAndFlush(EnrollmentFixtures.telemetryToken(installationId)) }

		val affected = telemetryTokens.revokeActiveByInstallationId(installationId, Instant.now())

		assertThat(affected).isEqualTo(3)
		assertThat(telemetryTokens.findAllByInstallationIdAndRevokedAtIsNull(installationId)).isEmpty()
	}

	@Test
	@DisplayName("이미 폐기된 토큰은 다시 세지 않는다")
	fun revokedTokensAreNotCountedAgain() {
		telemetryTokens.saveAndFlush(
			EnrollmentFixtures.telemetryToken(installationId, revokedAt = Instant.now()),
		)
		telemetryTokens.saveAndFlush(EnrollmentFixtures.telemetryToken(installationId))

		val affected = telemetryTokens.revokeActiveByInstallationId(installationId, Instant.now())

		assertThat(affected).isEqualTo(1)
	}

	@Test
	@DisplayName("활성 토큰이 없으면 0행이다 — 최초 발급 경로")
	fun revokeWithNoActiveTokens() {
		assertThat(telemetryTokens.revokeActiveByInstallationId(installationId, Instant.now()))
			.isEqualTo(0)
	}

	@Test
	@DisplayName("다른 installation 의 토큰은 건드리지 않는다")
	fun revokeDoesNotTouchOtherInstallations() {
		val otherInstallationId = newInstallation()
		telemetryTokens.saveAndFlush(EnrollmentFixtures.telemetryToken(installationId))
		telemetryTokens.saveAndFlush(EnrollmentFixtures.telemetryToken(otherInstallationId))

		val affected = telemetryTokens.revokeActiveByInstallationId(installationId, Instant.now())

		assertThat(affected).isEqualTo(1)
		assertThat(telemetryTokens.findAllByInstallationIdAndRevokedAtIsNull(otherInstallationId))
			.hasSize(1)
	}

	@Test
	@DisplayName("토큰 해시로 조회한다 — OTLP 헤더 검증 경로")
	fun findByTokenHash() {
		val tokenHash = EnrollmentFixtures.randomHash()
		telemetryTokens.saveAndFlush(EnrollmentFixtures.telemetryToken(installationId, tokenHash))

		val found = telemetryTokens.findByTokenHash(tokenHash)

		assertThat(found).isNotNull()
		assertThat(found!!.installationId).isEqualTo(installationId)
		assertThat(found.isRevoked()).isFalse()
	}
}
