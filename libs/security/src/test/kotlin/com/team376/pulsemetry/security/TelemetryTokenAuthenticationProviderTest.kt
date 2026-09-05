package com.team376.pulsemetry.security

import com.team376.pulsemetry.persistence.enrollment.entity.InstallationStatus
import com.team376.pulsemetry.persistence.enrollment.entity.MemberStatus
import com.team376.pulsemetry.persistence.enrollment.entity.TenantStatus
import com.team376.pulsemetry.persistence.enrollment.repository.InstallationRepository
import com.team376.pulsemetry.persistence.enrollment.repository.InvitationRepository
import com.team376.pulsemetry.persistence.enrollment.repository.MemberRepository
import com.team376.pulsemetry.persistence.enrollment.repository.TelemetryTokenRepository
import com.team376.pulsemetry.persistence.enrollment.repository.TenantRepository
import com.team376.pulsemetry.persistence.enrollment.support.EnrollmentFixtures
import com.team376.pulsemetry.security.support.AbstractSecurityIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.security.core.AuthenticationException
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * 실제 스키마 위에서 조회와 판정을 함께 본다.
 *
 * 순서와 극성 자체는 [TelemetryTokenDecisionTest] 가 표로 고정한다. 여기서 확인하는 것은
 * **네이티브 쿼리가 그 판정에 필요한 값을 제대로 실어 오는가**다 — 조인 방향, `::text` 투영,
 * 폐기 시각의 널 검사, 그리고 조회 키가 원문이 아니라 해시라는 것.
 */
@Transactional
class TelemetryTokenAuthenticationProviderTest : AbstractSecurityIntegrationTest() {

	@Autowired private lateinit var tenants: TenantRepository
	@Autowired private lateinit var members: MemberRepository
	@Autowired private lateinit var invitations: InvitationRepository
	@Autowired private lateinit var installations: InstallationRepository
	@Autowired private lateinit var telemetryTokens: TelemetryTokenRepository

	private val hasher = TelemetryTokenHasher("test-token-hash-secret")
	private lateinit var provider: TelemetryTokenAuthenticationProvider

	private val rawToken = "ptt_bxoBSKxLYQ0T3rIxbXpZDLtjKvKmVv0pDczAw1ipBmg"

	private lateinit var tenantId: UUID
	private lateinit var memberId: UUID
	private lateinit var installationId: UUID
	private lateinit var tokenId: UUID

	@BeforeEach
	fun setUp() {
		provider = TelemetryTokenAuthenticationProvider(hasher, telemetryTokens)

		val tenant = tenants.save(EnrollmentFixtures.tenant())
		val member = members.save(EnrollmentFixtures.member(tenantId = tenant.id))
		val invitation = invitations.save(
			EnrollmentFixtures.invitation(tenantId = tenant.id, memberId = member.id),
		)
		val installation = installations.save(
			EnrollmentFixtures.installation(
				tenantId = tenant.id,
				memberId = member.id,
				invitationId = invitation.id,
			),
		)
		val token = telemetryTokens.save(
			EnrollmentFixtures.telemetryToken(
				installationId = installation.id,
				tokenHash = hasher.hex(rawToken),
			),
		)

		tenantId = tenant.id
		memberId = member.id
		installationId = installation.id
		tokenId = token.id
	}

	@Test
	@DisplayName("통과하면 신원 네 값을 토큰에서 파생한다")
	fun mapsIdentity() {
		val authentication = provider.authenticate(
			TelemetryTokenAuthenticationToken.unauthenticated(rawToken),
		)

		assertThat(authentication.isAuthenticated).isTrue()
		assertThat(authentication.principal).isEqualTo(
			TelemetryTokenPrincipal(
				tokenId = tokenId,
				tenantId = tenantId,
				installationId = installationId,
				memberId = memberId,
			),
		)
		// 인증이 끝나면 원문을 들고 있을 이유가 없다.
		assertThat(authentication.credentials).isNull()
	}

	@Test
	@DisplayName("조회 키는 원문이 아니라 해시다")
	fun looksUpByHashNotPlaintext() {
		// 해시 문자열을 그대로 토큰이라고 내밀면 그 값을 한 번 더 해시한 결과로 조회되므로 빗나간다.
		// 어딘가에서 해시를 건너뛰면 이 테스트가 통과해 버린다.
		assertRejects(hasher.hex(rawToken), TelemetryTokenRejectionReason.TOKEN_UNKNOWN)
	}

	@Test
	@DisplayName("모르는 토큰은 token_unknown 이다")
	fun unknownToken() {
		assertRejects("ptt_nobody-issued-this", TelemetryTokenRejectionReason.TOKEN_UNKNOWN)
	}

	@Test
	@DisplayName("폐기된 토큰")
	fun revokedToken() {
		telemetryTokens.findById(tokenId).orElseThrow().revokedAt = Instant.now()

		assertRejects(rawToken, TelemetryTokenRejectionReason.TOKEN_REVOKED)
	}

	@Test
	@DisplayName("폐기된 installation — 폐기 시각이 상태보다 앞선다")
	fun revokedInstallation() {
		installations.findById(installationId).orElseThrow().revokedAt = Instant.now()

		assertRejects(rawToken, TelemetryTokenRejectionReason.INSTALLATION_REVOKED)
	}

	@Test
	@DisplayName("active 가 아닌 installation — 폐기 시각 없이 상태만 revoked 인 경우")
	fun inactiveInstallation() {
		// installation_status enum 은 ('active','revoked') 둘뿐이라 이 사유는 이 조합에서만 도달한다.
		installations.findById(installationId).orElseThrow().status = InstallationStatus.revoked

		assertRejects(rawToken, TelemetryTokenRejectionReason.INSTALLATION_INACTIVE)
	}

	@Test
	@DisplayName("정지된 멤버")
	fun suspendedMember() {
		members.findById(memberId).orElseThrow().status = MemberStatus.suspended

		assertRejects(rawToken, TelemetryTokenRejectionReason.MEMBER_SUSPENDED)
	}

	@Test
	@DisplayName("초대 상태 멤버 — enroll 로 active 전환이 안 됐다")
	fun invitedMember() {
		members.findById(memberId).orElseThrow().status = MemberStatus.invited

		assertRejects(rawToken, TelemetryTokenRejectionReason.MEMBER_INVITED)
	}

	@Test
	@DisplayName("삭제된 tenant — 삭제 시각이 상태보다 앞선다")
	fun deletedTenant() {
		tenants.findById(tenantId).orElseThrow().apply {
			deletedAt = Instant.now()
			status = TenantStatus.suspended
		}

		assertRejects(rawToken, TelemetryTokenRejectionReason.TENANT_DELETED)
	}

	@Test
	@DisplayName("정지된 tenant")
	fun suspendedTenant() {
		tenants.findById(tenantId).orElseThrow().status = TenantStatus.suspended

		assertRejects(rawToken, TelemetryTokenRejectionReason.TENANT_SUSPENDED)
	}

	@Test
	@DisplayName("해지된 tenant")
	fun terminatedTenant() {
		tenants.findById(tenantId).orElseThrow().status = TenantStatus.terminated

		assertRejects(rawToken, TelemetryTokenRejectionReason.TENANT_TERMINATED)
	}

	@Test
	@DisplayName("멤버 판정이 tenant 판정보다 앞선다 — 실제 스키마에서도 같다")
	fun memberBeatsTenant() {
		members.findById(memberId).orElseThrow().status = MemberStatus.suspended
		tenants.findById(tenantId).orElseThrow().status = TenantStatus.terminated

		assertRejects(rawToken, TelemetryTokenRejectionReason.MEMBER_SUSPENDED)
	}

	@Test
	@DisplayName("DB 오류는 401 로 접히지 않고 그대로 올라온다 — 장애가 '토큰이 틀렸다'로 보이면 안 된다")
	fun databaseFailurePropagates() {
		val broken = Mockito.mock(TelemetryTokenRepository::class.java)
		Mockito.`when`(broken.findAuthRowByTokenHash(Mockito.anyString()))
			.thenThrow(DataAccessResourceFailureException("connection refused"))
		val provider = TelemetryTokenAuthenticationProvider(hasher, broken)

		assertThatThrownBy {
			provider.authenticate(TelemetryTokenAuthenticationToken.unauthenticated(rawToken))
		}
			.isInstanceOf(DataAccessResourceFailureException::class.java)
			.isNotInstanceOf(AuthenticationException::class.java)
	}

	private fun assertRejects(token: String, reason: TelemetryTokenRejectionReason) {
		telemetryTokens.flush()

		assertThatThrownBy {
			provider.authenticate(TelemetryTokenAuthenticationToken.unauthenticated(token))
		}
			.isInstanceOf(TelemetryTokenAuthenticationException::class.java)
			.extracting { (it as TelemetryTokenAuthenticationException).reason }
			.isEqualTo(reason)
	}
}
