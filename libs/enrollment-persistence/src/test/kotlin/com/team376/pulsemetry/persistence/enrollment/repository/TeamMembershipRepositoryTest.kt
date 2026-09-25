package com.team376.pulsemetry.persistence.enrollment.repository

import com.team376.pulsemetry.persistence.enrollment.entity.TeamStatus
import com.team376.pulsemetry.persistence.enrollment.support.AbstractPersistenceIntegrationTest
import com.team376.pulsemetry.persistence.enrollment.support.EnrollmentFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 관측 보강의 as-of 조인이 딛는 두 축을 본다 — 구성원의 소속 이력을 읽어 오는 조회와,
 * 그 이력을 이벤트 시각으로 자르는 경계 판정이다 (허브 `contracts/data-model.md` D-3 · ADR 0020 §5).
 *
 * 경계는 **좌폐우개**(`joined_at <= at < left_at`)다. 현행 파이프라인의
 * `test_org_provider.py` 가 고정한 것과 같은 경계이며, 이관 후에도 같아야 한다.
 */
@Transactional
class TeamMembershipRepositoryTest : AbstractPersistenceIntegrationTest() {

	@Autowired
	private lateinit var tenants: TenantRepository

	@Autowired
	private lateinit var members: MemberRepository

	@Autowired
	private lateinit var teams: TeamRepository

	@Autowired
	private lateinit var memberships: TeamMembershipRepository

	@Autowired
	private lateinit var jdbcClient: JdbcClient

	private lateinit var tenantId: UUID
	private lateinit var memberId: UUID

	/** 소속 판정의 기준 시각. 아래 구간들은 전부 이 시각을 기준으로 놓인다. */
	private val at: Instant = Instant.parse("2026-06-01T00:00:00Z")

	@BeforeEach
	fun setUp() {
		tenantId = tenants.saveAndFlush(EnrollmentFixtures.tenant()).id
		memberId = members.saveAndFlush(EnrollmentFixtures.member(tenantId)).id
	}

	// ── 매핑 ─────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("team status 가 소문자 그대로 저장된다 — native enum 라벨과 맞물린다")
	fun teamStatusIsStoredLowercase() {
		val teamId = teams.saveAndFlush(EnrollmentFixtures.team(tenantId, status = TeamStatus.archived)).id

		val stored = jdbcClient
			.sql("SELECT status FROM enrollment.teams WHERE id = :id")
			.param("id", teamId)
			.query(String::class.java)
			.single()

		assertThat(stored).isEqualTo("archived")
	}

	@Test
	@DisplayName("소속 중인 구성원의 left_at 은 NULL 로 남는다")
	fun openEndedMembershipHasNullLeftAt() {
		val teamId = teams.saveAndFlush(EnrollmentFixtures.team(tenantId)).id
		val membershipId = memberships.saveAndFlush(EnrollmentFixtures.teamMembership(teamId, memberId)).id

		val leftAt = jdbcClient
			.sql("SELECT left_at FROM enrollment.team_memberships WHERE id = :id")
			.param("id", membershipId)
			.query(OffsetDateTime::class.java)
			.optional()

		assertThat(leftAt).isEmpty()
	}

	@Test
	@DisplayName("구성원의 소속 이력을 전부 가져온다")
	fun findsAllMembershipsOfMember() {
		val teamA = teams.saveAndFlush(EnrollmentFixtures.team(tenantId, name = "A팀")).id
		val teamB = teams.saveAndFlush(EnrollmentFixtures.team(tenantId, name = "B팀")).id
		memberships.saveAndFlush(EnrollmentFixtures.teamMembership(teamA, memberId, leftAt = at))
		memberships.saveAndFlush(EnrollmentFixtures.teamMembership(teamB, memberId, joinedAt = at))

		assertThat(memberships.findAllByMemberId(memberId))
			.extracting<UUID> { it.teamId }
			.containsExactlyInAnyOrder(teamA, teamB)
	}

	@Test
	@DisplayName("구성원의 소속 이력은 팀 상태로 거르지 않는다 — archived 팀의 과거 소속도 온다")
	fun memberHistoryKeepsArchivedTeams() {
		val archived = teams
			.saveAndFlush(EnrollmentFixtures.team(tenantId, name = "해체된팀", status = TeamStatus.archived))
			.id
		val active = teams.saveAndFlush(EnrollmentFixtures.team(tenantId, name = "살아있는팀")).id
		memberships.saveAndFlush(
			EnrollmentFixtures.teamMembership(archived, memberId, joinedAt = at.minus(90, ChronoUnit.DAYS), leftAt = at),
		)
		memberships.saveAndFlush(EnrollmentFixtures.teamMembership(active, memberId, joinedAt = at))

		assertThat(memberships.findAllByMemberId(memberId))
			.extracting<UUID> { it.teamId }
			.containsExactlyInAnyOrder(archived, active)
	}

	// ── 구성원 → 소속 이력 조회 ─────────────────────────────────────────────

	@Test
	@DisplayName("다른 구성원의 소속은 섞이지 않는다")
	fun otherMembersMembershipsAreNotReturned() {
		val teamId = teams.saveAndFlush(EnrollmentFixtures.team(tenantId)).id
		val otherMemberId = members.saveAndFlush(EnrollmentFixtures.member(tenantId)).id
		memberships.saveAndFlush(EnrollmentFixtures.teamMembership(teamId, otherMemberId))

		assertThat(memberships.findAllByMemberId(memberId)).isEmpty()
	}

	// ── as-of 경계 (좌폐우개) ────────────────────────────────────────────────

	@Test
	@DisplayName("합류 당일은 소속이다 — 왼쪽 경계는 닫혀 있다")
	fun joinedAtBoundaryIsInclusive() {
		val membership = EnrollmentFixtures.teamMembership(
			teams.saveAndFlush(EnrollmentFixtures.team(tenantId)).id,
			memberId,
			joinedAt = at,
		)

		assertThat(membership.coversAt(at)).isTrue()
		assertThat(membership.coversAt(at.minusMillis(1))).isFalse()
	}

	@Test
	@DisplayName("떠난 시각은 소속이 아니다 — 오른쪽 경계는 열려 있다")
	fun leftAtBoundaryIsExclusive() {
		val membership = EnrollmentFixtures.teamMembership(
			teams.saveAndFlush(EnrollmentFixtures.team(tenantId)).id,
			memberId,
			joinedAt = at.minus(30, ChronoUnit.DAYS),
			leftAt = at,
		)

		assertThat(membership.coversAt(at.minusMillis(1))).isTrue()
		assertThat(membership.coversAt(at)).isFalse()
	}

	@Test
	@DisplayName("한 시각에 여러 팀에 속할 수 있다 — team_ids_as_of 가 배열인 이유다")
	fun aMemberCanBelongToSeveralTeamsAtOnce() {
		val teamA = teams.saveAndFlush(EnrollmentFixtures.team(tenantId, name = "A팀")).id
		val teamB = teams.saveAndFlush(EnrollmentFixtures.team(tenantId, name = "B팀")).id
		memberships.saveAndFlush(
			EnrollmentFixtures.teamMembership(teamA, memberId, joinedAt = at.minus(10, ChronoUnit.DAYS)),
		)
		memberships.saveAndFlush(
			EnrollmentFixtures.teamMembership(teamB, memberId, joinedAt = at.minus(5, ChronoUnit.DAYS)),
		)

		val teamIdsAsOf = memberships
			.findAllByMemberId(memberId)
			.filter { it.coversAt(at) }
			.map { it.teamId }

		assertThat(teamIdsAsOf).containsExactlyInAnyOrder(teamA, teamB)
	}

	@Test
	@DisplayName("이력이 모두 기준 시각을 비껴가면 소속이 없다")
	fun noMembershipCoversTheInstant() {
		val teamId = teams.saveAndFlush(EnrollmentFixtures.team(tenantId)).id
		memberships.saveAndFlush(
			EnrollmentFixtures.teamMembership(
				teamId,
				memberId,
				joinedAt = at.plus(1, ChronoUnit.DAYS),
			),
		)

		val teamIdsAsOf = memberships
			.findAllByMemberId(memberId)
			.filter { it.coversAt(at) }

		assertThat(teamIdsAsOf).isEmpty()
	}
}
