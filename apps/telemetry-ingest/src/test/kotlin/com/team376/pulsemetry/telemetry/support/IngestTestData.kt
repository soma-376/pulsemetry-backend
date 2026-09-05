package com.team376.pulsemetry.telemetry.support

import com.team376.pulsemetry.persistence.enrollment.repository.InstallationRepository
import com.team376.pulsemetry.persistence.enrollment.repository.InvitationRepository
import com.team376.pulsemetry.persistence.enrollment.repository.MemberRepository
import com.team376.pulsemetry.persistence.enrollment.repository.TeamMembershipRepository
import com.team376.pulsemetry.persistence.enrollment.repository.TeamRepository
import com.team376.pulsemetry.persistence.enrollment.repository.TelemetryTokenRepository
import com.team376.pulsemetry.persistence.enrollment.repository.TenantRepository
import com.team376.pulsemetry.persistence.enrollment.support.EnrollmentFixtures
import com.team376.pulsemetry.security.TelemetryTokenHasher
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * OTLP 요청 하나가 `enriched_events` 행이 되기까지 필요한 신원 사슬을 심는다.
 *
 * ```
 * tenant → member → team → team_membership(as-of)
 *                └→ invitation → installation → telemetry_token(HMAC)
 * ```
 *
 * `team_membership` 이 **초대 대상 member** 에 걸려야 보강이 팀을 찾는다 —
 * `TeamMembershipRepository.findActiveTeamMembershipsByInstallationId` 가
 * `installations → members → team_memberships` 로 조인하기 때문이다.
 *
 * **`POST /v1/enroll` 을 부르지 않는다.** 그 엔드포인트는 `:apps:enrollment-api` 에 있고
 * 앱끼리는 의존하지 않으므로 이 컨텍스트에 올릴 수 없다. 토큰은 발급과 검증이 공유하는
 * [TelemetryTokenHasher] 로 직접 만든다 — 두 앱을 잇는 진짜 앵커는 그 해셔의 고정 벡터
 * 테스트다. 데몬까지 태우는 진짜 E2E 는 수동 절차로 남는다(`docs/enrollment-server-spec.md` §10.1).
 *
 * 컴포넌트 스캔에 걸리지 않게 `@TestConfiguration` 이고, 쓰는 테스트가 `@Import` 로 가져간다.
 */
@TestConfiguration(proxyBeanMethods = false)
class IngestTestData(
	private val tenants: TenantRepository,
	private val members: MemberRepository,
	private val teams: TeamRepository,
	private val teamMemberships: TeamMembershipRepository,
	private val invitations: InvitationRepository,
	private val installations: InstallationRepository,
	private val telemetryTokens: TelemetryTokenRepository,
	private val hasher: TelemetryTokenHasher,
	private val jdbc: JdbcTemplate,
) {

	/** 심은 신원과 그것으로 인증할 수 있는 원문 토큰. */
	data class Seeded(
		val tenantId: UUID,
		val memberId: UUID,
		val teamId: UUID,
		val installationId: UUID,
		val rawToken: String,
	)

	fun seed(): Seeded {
		val tenant = tenants.save(EnrollmentFixtures.tenant())
		val member = members.save(EnrollmentFixtures.member(tenantId = tenant.id))
		val team = teams.save(EnrollmentFixtures.team(tenantId = tenant.id))
		teamMemberships.save(
			EnrollmentFixtures.teamMembership(
				teamId = team.id,
				memberId = member.id,
				// 이벤트 시각을 확실히 덮는 구간이다. as-of 판정이 걸러 내면 team_ids 가 빈다.
				joinedAt = Instant.now().minus(365, ChronoUnit.DAYS),
				leftAt = null,
			),
		)
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

		// 원문은 시드에 박지 않고 매번 만든다. 저장하는 것은 해시뿐이다.
		val rawToken = "ptt_" + UUID.randomUUID().toString().replace("-", "")
		telemetryTokens.save(
			EnrollmentFixtures.telemetryToken(
				installationId = installation.id,
				tokenHash = hasher.hex(rawToken),
			),
		)

		return Seeded(
			tenantId = tenant.id,
			memberId = member.id,
			teamId = team.id,
			installationId = installation.id,
			rawToken = rawToken,
		)
	}

	/** 테스트마다 비운다. HTTP 테스트는 트랜잭션 롤백이 없어 커밋된 채로 남는다. */
	fun clear() {
		jdbc.execute(
			"""
			TRUNCATE TABLE
			  enrollment.telemetry_tokens,
			  enrollment.installation_credentials,
			  enrollment.installation_manifest_assignments,
			  enrollment.installations,
			  enrollment.invitations,
			  enrollment.team_memberships,
			  enrollment.teams,
			  enrollment.manifests,
			  enrollment.members,
			  enrollment.tenants
			CASCADE
			""".trimIndent(),
		)
	}
}
