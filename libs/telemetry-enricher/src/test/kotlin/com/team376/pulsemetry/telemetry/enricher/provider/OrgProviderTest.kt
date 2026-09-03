package com.team376.pulsemetry.telemetry.enricher.provider

import com.team376.pulsemetry.persistence.enrollment.entity.TeamStatus
import com.team376.pulsemetry.persistence.enrollment.repository.InstallationRepository
import com.team376.pulsemetry.persistence.enrollment.repository.InvitationRepository
import com.team376.pulsemetry.persistence.enrollment.repository.MemberRepository
import com.team376.pulsemetry.persistence.enrollment.repository.TeamMembershipRepository
import com.team376.pulsemetry.persistence.enrollment.repository.TeamRepository
import com.team376.pulsemetry.persistence.enrollment.repository.TenantRepository
import com.team376.pulsemetry.persistence.enrollment.support.EnrollmentFixtures
import com.team376.pulsemetry.telemetry.enricher.Enriched
import com.team376.pulsemetry.telemetry.enricher.Enricher
import com.team376.pulsemetry.telemetry.enricher.support.AbstractEnricherIntegrationTest
import com.team376.pulsemetry.telemetry.enricher.support.TestEvents
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * `org` provider 의 as-of 조인을 실제 PostgreSQL 위에서 본다.
 *
 * 경계 판정 자체(`joined_at <= at < left_at`)는 `TeamMembershipRepositoryTest`(PROJ-101)가
 * 이미 고정했다. 여기서 보는 것은 **provider 가 그것을 어떻게 쓰는가**다 — miss 세 갈래가
 * 서로 다른 주석을 낸다는 것, push 단위 캐시, whitelist 컬럼 승격.
 *
 * 이식 원본은 `ai-telemetry-pipeline` 의 `tests/enrichment/test_org_provider.py` 다.
 */
class OrgProviderTest : AbstractEnricherIntegrationTest() {

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

	private lateinit var provider: OrgProvider
	private lateinit var tenantId: UUID
	private lateinit var memberId: UUID
	private lateinit var installationId: UUID

	/** 소속 판정의 기준 시각. */
	private val at: Instant = Instant.parse("2026-06-01T00:00:00Z")

	@BeforeEach
	fun setUp() {
		provider = OrgProvider(memberships)
		tenantId = tenants.saveAndFlush(EnrollmentFixtures.tenant()).id
		memberId = members.saveAndFlush(EnrollmentFixtures.member(tenantId)).id
		val invitationId = invitations.saveAndFlush(EnrollmentFixtures.invitation(tenantId, memberId)).id
		installationId = installations
			.saveAndFlush(EnrollmentFixtures.installation(tenantId, memberId, invitationId))
			.id
	}

	private fun enrichOne(
		installation: UUID? = installationId,
		instant: Instant = at,
		ctx: MutableMap<String, Any?> = HashMap(),
	): Pair<Enriched, Map<String, Any?>> {
		val item = Enriched(TestEvents.log(installation, instant))
		val annotation = provider.enrich(item, ctx)
		return item to annotation
	}

	private fun joinTeam(
		name: String,
		joinedAt: Instant = at.minus(30, ChronoUnit.DAYS),
		leftAt: Instant? = null,
		status: TeamStatus = TeamStatus.active,
	): UUID {
		val teamId = teams.saveAndFlush(EnrollmentFixtures.team(tenantId, name = name, status = status)).id
		memberships.saveAndFlush(EnrollmentFixtures.teamMembership(teamId, memberId, joinedAt, leftAt))
		return teamId
	}

	// ── 승격과 주석 ──────────────────────────────────────────────────────────

	@Test
	@DisplayName("소속 팀을 whitelist 컬럼과 주석 양쪽에 싣는다")
	fun promotesTeamIdsAndAnnotates() {
		val teamId = joinTeam("A팀")

		val (item, annotation) = enrichOne()

		assertThat(item.teamIdsAsOf).containsExactly(teamId.toString())
		assertThat(annotation).isEqualTo(mapOf("team_ids" to listOf(teamId.toString())))
	}

	@Test
	@DisplayName("한 시각에 여러 팀이면 전부 싣는다")
	fun keepsEveryConcurrentTeam() {
		val teamA = joinTeam("A팀")
		val teamB = joinTeam("B팀", joinedAt = at.minus(5, ChronoUnit.DAYS))

		val (item, _) = enrichOne()

		assertThat(item.teamIdsAsOf).containsExactlyInAnyOrder(teamA.toString(), teamB.toString())
	}

	@Test
	@DisplayName("팀 id 는 소문자 UUID 문자열이다 — 구 조회의 team_id::text 와 같은 표기")
	fun teamIdsAreLowercaseUuidStrings() {
		val teamId = joinTeam("A팀")

		val (item, _) = enrichOne()

		assertThat(item.teamIdsAsOf).containsExactly(teamId.toString().lowercase())
	}

	// ── as-of 경계가 provider 를 통해서도 같은가 ─────────────────────────────

	@Test
	@DisplayName("합류 전 이벤트는 그 팀에 귀속되지 않는다")
	fun eventBeforeJoinIsNotAttributed() {
		joinTeam("A팀", joinedAt = at.plus(1, ChronoUnit.DAYS))

		val (item, annotation) = enrichOne()

		assertThat(item.teamIdsAsOf).isEmpty()
		// 조회는 했다 — 빈 주석(`{}`)이 아니라 빈 목록이다. 이 구분이 이식의 판정 대상이다.
		assertThat(annotation).isEqualTo(mapOf("team_ids" to emptyList<String>()))
	}

	@Test
	@DisplayName("떠난 뒤 이벤트는 그 팀에 귀속되지 않고, 떠나기 직전 이벤트는 귀속된다")
	fun leftAtIsExclusive() {
		val teamId = joinTeam("A팀", joinedAt = at.minus(30, ChronoUnit.DAYS), leftAt = at)

		assertThat(enrichOne(instant = at.minusMillis(1)).first.teamIdsAsOf)
			.containsExactly(teamId.toString())
		assertThat(enrichOne(instant = at).first.teamIdsAsOf).isEmpty()
	}

	@Test
	@DisplayName("archived 팀의 소속은 애초에 조회에서 빠진다")
	fun archivedTeamIsExcluded() {
		joinTeam("해체된팀", status = TeamStatus.archived)
		val active = joinTeam("살아있는팀")

		val (item, _) = enrichOne()

		assertThat(item.teamIdsAsOf).containsExactly(active.toString())
	}

	// ── miss 세 갈래 ─────────────────────────────────────────────────────────

	@Test
	@DisplayName("installation_id 가 없으면 조회 자체를 하지 않는다 — 캐시 항목도 안 생긴다")
	fun missingInstallationIdSkipsTheLookup() {
		val ctx: MutableMap<String, Any?> = HashMap()

		val (item, annotation) = enrichOne(installation = null, ctx = ctx)

		assertThat(annotation).isEmpty()
		assertThat(item.teamIdsAsOf).isEmpty()
		assertThat(ctx).doesNotContainKey(OrgProvider.CACHE_KEY)
	}

	@Test
	@DisplayName("모르는 installation 은 조회 후 빈 목록이다 — 주석 모양이 위와 다르다")
	fun unknownInstallationYieldsAnEmptyList() {
		val ctx: MutableMap<String, Any?> = HashMap()

		val (_, annotation) = enrichOne(installation = UUID.randomUUID(), ctx = ctx)

		assertThat(annotation).isEqualTo(mapOf("team_ids" to emptyList<String>()))
		assertThat(ctx).containsKey(OrgProvider.CACHE_KEY)
	}

	@Test
	@DisplayName("소속 이력이 아예 없는 구성원도 빈 목록이다")
	fun memberWithoutMembershipsYieldsAnEmptyList() {
		val (_, annotation) = enrichOne()

		assertThat(annotation).isEqualTo(mapOf("team_ids" to emptyList<String>()))
	}

	// ── push 단위 캐시 ───────────────────────────────────────────────────────

	@Test
	@DisplayName("같은 installation 은 push 하나에 한 번만 조회한다")
	fun theLookupIsCachedWithinOnePush() {
		joinTeam("A팀")
		val ctx: MutableMap<String, Any?> = HashMap()

		enrichOne(ctx = ctx)
		// 캐시를 비워 두면 두 번째 이벤트가 다시 조회한다. 캐시가 가로채는지 보려고 값을 갈아 끼운다.
		@Suppress("UNCHECKED_CAST")
		val cache = ctx[OrgProvider.CACHE_KEY] as MutableMap<String, Any?>
		cache[installationId.toString()] = emptyList<Nothing>()

		val (item, _) = enrichOne(ctx = ctx)

		assertThat(item.teamIdsAsOf).isEmpty()
	}

	@Test
	@DisplayName("캐시는 push 를 넘어가지 않는다 — Enricher 가 호출마다 ctx 를 새로 만든다")
	fun theCacheDoesNotOutlivethePush() {
		val teamId = joinTeam("A팀")
		val enricher = Enricher(listOf(provider))
		val events = listOf(TestEvents.log(installationId, at), TestEvents.log(installationId, at))

		val first = enricher.enrich(events)
		val second = enricher.enrich(events)

		assertThat(first + second).allSatisfy { item ->
			assertThat(item.teamIdsAsOf).containsExactly(teamId.toString())
		}
	}
}
