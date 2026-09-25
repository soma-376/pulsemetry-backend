package com.team376.pulsemetry.telemetry.enricher.observation

import com.team376.pulsemetry.persistence.enrollment.entity.InstallationStatus
import com.team376.pulsemetry.persistence.enrollment.entity.TeamStatus
import com.team376.pulsemetry.persistence.enrollment.repository.InstallationRepository
import com.team376.pulsemetry.persistence.enrollment.repository.InvitationRepository
import com.team376.pulsemetry.persistence.enrollment.repository.MemberRepository
import com.team376.pulsemetry.persistence.enrollment.repository.TeamMembershipRepository
import com.team376.pulsemetry.persistence.enrollment.repository.TeamRepository
import com.team376.pulsemetry.persistence.enrollment.repository.TenantRepository
import com.team376.pulsemetry.persistence.enrollment.support.EnrollmentFixtures
import com.team376.pulsemetry.telemetry.adapter.observation.AnalysisHash
import com.team376.pulsemetry.telemetry.adapter.observation.QualityFlag
import com.team376.pulsemetry.telemetry.enricher.provider.AiAnalysisProvider
import com.team376.pulsemetry.telemetry.enricher.provider.GithubProvider
import com.team376.pulsemetry.telemetry.enricher.provider.JiraProvider
import com.team376.pulsemetry.telemetry.enricher.support.AbstractEnricherIntegrationTest
import com.team376.pulsemetry.telemetry.enricher.support.TestObservations
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 관측 보강 2판(ADR 0020 §5)을 실제 PostgreSQL 위에서 본다. 기대값은 그 절의 규칙에서 온다 — 구성원은 installation
 * 단독 조회, 대표 팀은 `source_time` 의 서로 다른 팀 ID 가 하나일 때만, 팀 상태는 보지 않는다.
 */
class ObservationEnricherTest : AbstractEnricherIntegrationTest() {

	@Autowired
	private lateinit var tenants: TenantRepository

	@Autowired
	private lateinit var members: MemberRepository

	@Autowired
	private lateinit var teams: TeamRepository

	@Autowired
	private lateinit var memberships: TeamMembershipRepository

	@Autowired
	private lateinit var invitations: InvitationRepository

	@Autowired
	private lateinit var installations: InstallationRepository

	private lateinit var enricher: ObservationEnricher
	private lateinit var tenantId: UUID
	private lateinit var memberId: UUID
	private lateinit var invitationId: UUID
	private lateinit var installationId: UUID

	/** 소속 판정의 기준 시각 — 관측의 `source_time`. */
	private val at: Instant = Instant.parse("2026-06-01T00:00:00Z")

	@BeforeEach
	fun setUp() {
		enricher = ObservationEnricher(installations, memberships, listOf(GithubProvider(), JiraProvider(), AiAnalysisProvider()))
		tenantId = tenants.saveAndFlush(EnrollmentFixtures.tenant()).id
		memberId = members.saveAndFlush(EnrollmentFixtures.member(tenantId)).id
		invitationId = invitations.saveAndFlush(EnrollmentFixtures.invitation(tenantId, memberId)).id
		installationId = installations.saveAndFlush(EnrollmentFixtures.installation(tenantId, memberId, invitationId)).id
	}

	private fun team(status: TeamStatus = TeamStatus.active): UUID =
		teams.saveAndFlush(EnrollmentFixtures.team(tenantId, status = status)).id

	private fun join(teamId: UUID, joinedAt: Instant = at.minus(30, ChronoUnit.DAYS), leftAt: Instant? = null) {
		memberships.saveAndFlush(EnrollmentFixtures.teamMembership(teamId, memberId, joinedAt = joinedAt, leftAt = leftAt))
	}

	private fun enrichOne(installation: String = installationId.toString(), instant: Instant = at): EnrichedEvent =
		enricher.enrich(TestObservations.batch(events = listOf(TestObservations.event(installation, instant)))).events.single()

	private fun enrichmentJson(vararg teamIds: UUID): String =
		"""{"ai_analysis":{},"github":{},"jira":{},"org":{"team_ids":[${teamIds.joinToString(",") { "\"$it\"" }}]}}"""

	// ── 최종 설계 사례 1·2·3·29 ────────────────────────────────────────────

	@Test
	@DisplayName("사례 1 — 무소속 구성원: member_id 는 채우고 팀은 null, 플래그 없음")
	fun memberWithoutTeam() {
		val enriched = enrichOne()

		assertThat(enriched.org.memberId).isEqualTo(memberId.toString())
		assertThat(enriched.org.teamIdAsOf).isNull()
		assertThat(enriched.org.teamIdsAsOf).isEmpty()
		assertThat(enriched.org.enrichmentJson).isEqualTo(enrichmentJson())
		assertThat(enriched.org.enrichmentVersion).isEqualTo(ObservationEnricher.ENRICHMENT_VERSION)
		assertThat(enriched.observation.envelope.qualityFlags).isEmpty()
	}

	@Test
	@DisplayName("사례 2 — archived 팀의 과거 소속: 이벤트 시점의 그 팀을 유지한다")
	fun archivedTeamKeepsPastAttribution() {
		val archived = team(TeamStatus.archived)
		join(archived, joinedAt = at.minus(90, ChronoUnit.DAYS), leftAt = at.plus(1, ChronoUnit.DAYS))

		val enriched = enrichOne()

		assertThat(enriched.org.teamIdAsOf).isEqualTo(archived.toString())
		assertThat(enriched.org.teamIdsAsOf).containsExactly(archived.toString())
		assertThat(enriched.org.enrichmentJson).isEqualTo(enrichmentJson(archived))
		assertThat(enriched.observation.envelope.qualityFlags).isEmpty()
	}

	@Test
	@DisplayName("사례 3 — 두 팀 동시 소속: 대표 팀 null, 목록은 두 팀(정렬), multi_team_membership")
	fun twoTeamsAtOnce() {
		val a = team()
		val b = team()
		join(a)
		join(b, joinedAt = at.minus(5, ChronoUnit.DAYS))
		val sorted = listOf(a, b).sortedBy { it.toString() }

		val enriched = enrichOne()

		assertThat(enriched.org.teamIdAsOf).isNull()
		assertThat(enriched.org.teamIdsAsOf).containsExactly(*sorted.map { it.toString() }.toTypedArray())
		assertThat(enriched.org.enrichmentJson).isEqualTo(enrichmentJson(*sorted.toTypedArray()))
		assertThat(enriched.observation.envelope.qualityFlags).containsExactly(QualityFlag.MULTI_TEAM_MEMBERSHIP)
	}

	@Test
	@DisplayName("사례 29 — 같은 팀의 겹친 소속 행 둘: 한 팀으로 보고 플래그가 없다")
	fun duplicateMembershipOfSameTeam() {
		val a = team()
		join(a, joinedAt = at.minus(30, ChronoUnit.DAYS))
		join(a, joinedAt = at.minus(10, ChronoUnit.DAYS))

		val enriched = enrichOne()

		assertThat(enriched.org.teamIdAsOf).isEqualTo(a.toString())
		assertThat(enriched.org.teamIdsAsOf).containsExactly(a.toString())
		assertThat(enriched.observation.envelope.qualityFlags).isEmpty()
	}

	// ── 구성원 조회 ────────────────────────────────────────────────────────

	@Test
	@DisplayName("revoked installation 도 구성원과 소속을 채운다 — 과거 사용량은 그 사람의 것이다")
	fun revokedInstallationIsAttributed() {
		val revoked = installations.saveAndFlush(
			EnrollmentFixtures.installation(tenantId, memberId, invitationId).apply {
				status = InstallationStatus.revoked
				revokedAt = at.plus(1, ChronoUnit.DAYS)
			},
		).id
		val a = team()
		join(a)

		val enriched = enrichOne(installation = revoked.toString())

		assertThat(enriched.org.memberId).isEqualTo(memberId.toString())
		assertThat(enriched.org.teamIdAsOf).isEqualTo(a.toString())
		assertThat(enriched.observation.envelope.qualityFlags).isEmpty()
	}

	@Test
	@DisplayName("installation 이 없으면 member_id null + member_unresolved, 팀 필드는 비운다")
	fun unknownInstallationIsUnresolved() {
		val enriched = enrichOne(installation = UUID.randomUUID().toString())

		assertThat(enriched.org.memberId).isNull()
		assertThat(enriched.org.teamIdAsOf).isNull()
		assertThat(enriched.org.teamIdsAsOf).isEmpty()
		assertThat(enriched.org.enrichmentJson).isEqualTo(enrichmentJson())
		assertThat(enriched.observation.envelope.qualityFlags).containsExactly(QualityFlag.MEMBER_UNRESOLVED)
	}

	@Test
	@DisplayName("UUID 가 아닌 installation_id 도 미해결이다 — 던지지 않는다")
	fun malformedInstallationIsUnresolved() {
		val enriched = enrichOne(installation = "not-a-uuid")

		assertThat(enriched.org.memberId).isNull()
		assertThat(enriched.observation.envelope.qualityFlags).containsExactly(QualityFlag.MEMBER_UNRESOLVED)
	}

	// ── as-of 경계 ─────────────────────────────────────────────────────────

	@Test
	@DisplayName("소속 경계 — joined_at 은 포함, left_at 은 제외. 한 push 안에서 관측마다 자기 source_time 으로 판정한다")
	fun boundariesPerObservation() {
		val a = team()
		val joinedAt = at.minus(10, ChronoUnit.DAYS)
		join(a, joinedAt = joinedAt, leftAt = at)
		val instants = listOf(joinedAt.minusNanos(1), joinedAt, at.minusNanos(1), at)

		val enriched = enricher.enrich(
			TestObservations.batch(events = instants.map { TestObservations.event(installationId.toString(), it) }),
		).events

		assertThat(enriched.map { it.org.teamIdAsOf }).containsExactly(null, a.toString(), a.toString(), null)
		assertThat(enriched.map { it.org.memberId }).containsOnly(memberId.toString())
	}

	// ── 불변 ───────────────────────────────────────────────────────────────

	@Test
	@DisplayName("observation_id·analysis_hash 는 그대로다 — 보강 플래그를 더한 뒤에도 hash 가 다시 맞는다")
	fun identityAndHashAreUntouched() {
		val a = team()
		val b = team()
		join(a)
		join(b)
		val input = TestObservations.event(UUID.randomUUID().toString(), at)
		val multi = TestObservations.event(installationId.toString(), at)

		val enriched = enricher.enrich(TestObservations.batch(events = listOf(input, multi))).events

		for ((before, after) in listOf(input, multi).zip(enriched)) {
			assertThat(after.observation.envelope.observationId).isEqualTo(before.envelope.observationId)
			assertThat(after.observation.envelope.analysisHash).isEqualTo(before.envelope.analysisHash)
			assertThat(AnalysisHash.of(after.observation)).isEqualTo(before.envelope.analysisHash)
		}
		assertThat(enriched.map { it.observation.envelope.qualityFlags })
			.containsExactly(listOf(QualityFlag.MEMBER_UNRESOLVED), listOf(QualityFlag.MULTI_TEAM_MEMBERSHIP))
	}

	@Test
	@DisplayName("정규화가 붙인 플래그는 보존하고 보강 플래그를 선언 순서로 더한다")
	fun existingFlagsArePreserved() {
		val input = TestObservations.event(UUID.randomUUID().toString(), at, flags = listOf(QualityFlag.PROVIDER_UNRESOLVED))

		val enriched = enricher.enrich(TestObservations.batch(events = listOf(input))).events.single()

		assertThat(enriched.observation.envelope.qualityFlags)
			.containsExactly(QualityFlag.PROVIDER_UNRESOLVED, QualityFlag.MEMBER_UNRESOLVED)
	}

	@Test
	@DisplayName("metric point 도 같은 규칙으로 보강한다 — 행 수는 입력과 같다")
	fun metricPointsAreAttributed() {
		val a = team()
		join(a)

		val batch = enricher.enrich(
			TestObservations.batch(
				events = listOf(TestObservations.event(installationId.toString(), at)),
				metricPoints = listOf(
					TestObservations.metricPoint(installationId.toString(), at),
					TestObservations.metricPoint(UUID.randomUUID().toString(), at),
				),
			),
		)

		assertThat(batch.events).hasSize(1)
		assertThat(batch.metricPoints.map { it.org.teamIdAsOf }).containsExactly(a.toString(), null)
		assertThat(batch.metricPoints.map { it.observation.envelope.qualityFlags })
			.containsExactly(emptyList(), listOf(QualityFlag.MEMBER_UNRESOLVED))
	}
}
