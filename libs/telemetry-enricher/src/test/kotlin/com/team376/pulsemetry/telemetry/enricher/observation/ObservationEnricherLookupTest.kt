package com.team376.pulsemetry.telemetry.enricher.observation

import com.team376.pulsemetry.persistence.enrollment.entity.TeamMembership
import com.team376.pulsemetry.persistence.enrollment.repository.InstallationMemberRow
import com.team376.pulsemetry.persistence.enrollment.repository.InstallationRepository
import com.team376.pulsemetry.persistence.enrollment.repository.TeamMembershipRepository
import com.team376.pulsemetry.telemetry.model.ObservationEnvelope
import com.team376.pulsemetry.telemetry.model.QualityFlag
import com.team376.pulsemetry.telemetry.enricher.EnrichmentUnavailableException
import com.team376.pulsemetry.telemetry.enricher.provider.EnrichmentProvider
import com.team376.pulsemetry.telemetry.enricher.provider.GithubProvider
import com.team376.pulsemetry.telemetry.enricher.support.TestObservations
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.ArgumentMatchers.anyCollection
import org.mockito.BDDMockito.willAnswer
import org.mockito.BDDMockito.willThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.InvalidDataAccessResourceUsageException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.dao.QueryTimeoutException
import org.springframework.dao.RecoverableDataAccessException
import org.springframework.transaction.CannotCreateTransactionException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 조회 횟수·오류 분류·provider 목록 — DB 없이 볼 수 있는 것. 오류 분류를 좁게 고정한다. **넓히지 마라.** 일시
 * 장애만 [EnrichmentUnavailableException](앱이 503)이고 영구 오류는 그대로 전파된다. 적재 단계도 같은 원칙이다(허브
 * ADR 0006) — `ClickHouseErrorClassificationTest` 와 나란히 읽는다.
 */
class ObservationEnricherLookupTest {

	private val installationId: UUID = UUID.randomUUID()
	private val memberId: UUID = UUID.randomUUID()
	private val at: Instant = Instant.parse("2026-06-01T00:00:00Z")

	private val installations: InstallationRepository = mock(InstallationRepository::class.java)
	private val memberships: TeamMembershipRepository = mock(TeamMembershipRepository::class.java)

	private fun enricher(providers: List<EnrichmentProvider> = emptyList()) = ObservationEnricher(installations, memberships, providers)

	private fun batch(vararg installation: UUID) =
		TestObservations.batch(events = installation.map { TestObservations.event(it.toString(), at) })

	// ── push 단위 캐시 ─────────────────────────────────────────────────────

	@Test
	@DisplayName("installation 은 push 하나에 한 번만 조회한다 — 다음 push 는 다시 읽는다")
	fun lookupIsCachedPerPush() {
		given(installations.findMemberIdById(installationId)).willReturn(memberId)
		given(memberships.findAllByMemberId(memberId)).willReturn(emptyList())
		val enricher = enricher()

		enricher.enrich(
			TestObservations.batch(
				events = List(3) { TestObservations.event(installationId.toString(), at.plusSeconds(it.toLong())) },
				metricPoints = listOf(TestObservations.metricPoint(installationId.toString(), at)),
			),
		)
		verify(installations, times(1)).findMemberIdById(installationId)
		verify(memberships, times(1)).findAllByMemberId(memberId)

		enricher.enrich(batch(installationId))
		verify(installations, times(2)).findMemberIdById(installationId)
	}

	@Test
	@DisplayName("한 push 의 여러 배치(제품 문서마다)는 조회 캐시 하나를 쓰고 순서를 지킨다")
	fun batchesOfOnePushShareTheCache() {
		given(installations.findMemberIdById(installationId)).willReturn(memberId)
		given(memberships.findAllByMemberId(memberId)).willReturn(emptyList())
		val other = UUID.randomUUID()

		val enriched = enricher().enrich(listOf(batch(installationId), batch(other, installationId)))

		assertThat(enriched.map { it.events.size }).containsExactly(1, 2)
		assertThat(enriched[1].events.map { it.org.memberId }).containsExactly(null, memberId.toString())
		verify(installations, times(1)).findMemberIdById(installationId)
	}

	@Test
	@DisplayName("구성원이 없으면 소속 이력을 읽지 않는다")
	fun noMembershipLookupWithoutMember() {
		enricher().enrich(batch(installationId))

		verifyNoInteractions(memberships)
	}

	// ── 배치 소비 호출 단위 캐시(enrichAll · ADR 0084) ─────────────────────

	/**
	 * 묶음 조회가 [members](installation → 구성원)와 [histories](구성원 → 소속)에서 요청한 ID 의 행만 돌려주게 건다. 앞서 건 실패를 덮어쓸
	 * 수 있게 호출하지 않는 꼴(`willAnswer … given`)이다.
	 */
	private fun stubBulk(members: Map<UUID, UUID>, histories: Map<UUID, List<TeamMembership>> = emptyMap()) {
		willAnswer { invocation ->
			invocation.getArgument<Collection<UUID>>(0).mapNotNull { id -> members[id]?.let { row(id, it) } }
		}.given(installations).findMemberIdsByIds(anyCollection())
		willAnswer { invocation ->
			invocation.getArgument<Collection<UUID>>(0).flatMap { histories[it].orEmpty() }
		}.given(memberships).findAllByMemberIdIn(anyCollection())
	}

	private fun row(installation: UUID, member: UUID): InstallationMemberRow = object : InstallationMemberRow {
		override val installationId: UUID = installation
		override val memberId: UUID = member
	}

	@Test
	@DisplayName("enrichAll 은 호출마다 구성원·소속을 IN 조회 한 번씩만 읽는다 — 다음 호출은 다시 읽고, 단건 조회는 쓰지 않는다")
	fun enrichAllLooksUpOncePerCall() {
		val other = UUID.randomUUID()
		val teamId = UUID.randomUUID()
		stubBulk(mapOf(installationId to memberId), mapOf(memberId to listOf(TeamMembership(teamId, memberId, joinedAt = at.minus(1, ChronoUnit.DAYS)))))
		val enricher = enricher()
		val pushes = listOf(
			listOf(
				TestObservations.batch(
					events = List(3) { TestObservations.event(installationId.toString(), at.plusSeconds(it.toLong())) },
					metricPoints = listOf(TestObservations.metricPoint(installationId.toString(), at)),
				),
			),
			listOf(batch(other), batch(installationId)),
			listOf(batch(installationId, other)),
		)

		val enriched = enricher.enrichAll(pushes)

		verify(installations, times(1)).findMemberIdsByIds(setOf(installationId, other))
		verify(memberships, times(1)).findAllByMemberIdIn(setOf(memberId))
		verify(installations, never()).findMemberIdById(installationId)
		verify(memberships, never()).findAllByMemberId(memberId)
		assertThat(enriched.map { push -> push.map { it.events.size } }).containsExactly(listOf(3), listOf(1, 1), listOf(2))
		assertThat(enriched[1][0].events.single().org.memberId).isNull()
		assertThat(enriched[1][1].events.single().org.teamIdAsOf).isEqualTo(teamId.toString())
		assertThat(enriched[2][0].events.map { it.org.memberId }).containsExactly(memberId.toString(), null)

		enricher.enrichAll(pushes)
		verify(installations, times(2)).findMemberIdsByIds(setOf(installationId, other))
		verify(memberships, times(2)).findAllByMemberIdIn(setOf(memberId))
	}

	@Test
	@DisplayName("enrichAll 의 결과는 push 마다 enrich 를 부른 것과 같다 — 구성원·대표 팀·플래그·enrichment_json")
	fun enrichAllMatchesEnrichPerPush() {
		val other = UUID.randomUUID()
		val history = listOf(
			TeamMembership(UUID.randomUUID(), memberId, joinedAt = at.minus(10, ChronoUnit.DAYS)),
			TeamMembership(UUID.randomUUID(), memberId, joinedAt = at.minus(5, ChronoUnit.DAYS), leftAt = at.plusSeconds(1)),
		)
		stubBulk(mapOf(installationId to memberId), mapOf(memberId to history))
		given(installations.findMemberIdById(installationId)).willReturn(memberId)
		given(memberships.findAllByMemberId(memberId)).willReturn(history)
		val pushes = listOf(
			listOf(
				TestObservations.batch(
					events = listOf(TestObservations.event(installationId.toString(), at), TestObservations.event(installationId.toString(), at.plusSeconds(2))),
					metricPoints = listOf(TestObservations.metricPoint(other.toString(), at), TestObservations.metricPoint(installationId.toString(), at)),
				),
			),
			listOf(batch(other, installationId), TestObservations.batch(events = listOf(TestObservations.event("not-a-uuid", at)))),
		)
		val enricher = enricher(listOf(GithubProvider()))

		val bulk = enricher.enrichAll(pushes)
		val single = pushes.map { enricher.enrich(it) }

		assertThat(bulk.map { push -> push.map { it.events } }).isEqualTo(single.map { push -> push.map { it.events } })
		assertThat(bulk.map { push -> push.map { it.metricPoints } }).isEqualTo(single.map { push -> push.map { it.metricPoints } })
		assertThat(bulk[0][0].events.map { it.observation.envelope.qualityFlags })
			.containsExactly(listOf(QualityFlag.MULTI_TEAM_MEMBERSHIP), emptyList())
	}

	@Test
	@DisplayName("enrichAll 은 빈 입력·관측 없는 push·UUID 가 아닌 ID 만이면 조회하지 않는다")
	fun enrichAllSkipsLookupsWithoutResolvableIds() {
		val enricher = enricher()

		assertThat(enricher.enrichAll(emptyList())).isEmpty()
		assertThat(enricher.enrichAll(listOf(emptyList(), listOf(TestObservations.batch()))).map { it.size }).containsExactly(0, 1)
		val malformed = enricher.enrichAll(listOf(listOf(TestObservations.batch(events = listOf(TestObservations.event("not-a-uuid", at))))))

		assertThat(malformed.single().single().events.single().observation.envelope.qualityFlags).containsExactly(QualityFlag.MEMBER_UNRESOLVED)
		verifyNoInteractions(installations, memberships)
	}

	@Test
	@DisplayName("enrichAll 은 구성원이 없는 설치만이면 소속을 읽지 않는다")
	fun enrichAllSkipsMembershipsWithoutMembers() {
		stubBulk(emptyMap())

		val enriched = enricher().enrichAll(listOf(listOf(batch(installationId))))

		assertThat(enriched.single().single().events.single().org.memberId).isNull()
		verify(memberships, never()).findAllByMemberIdIn(anyCollection())
	}

	@Test
	@DisplayName("enrichAll 의 provider 문맥은 push 마다 새로 시작한다 — 호출 전체가 함께 쓰는 것은 구성원·소속 캐시뿐이다")
	fun enrichAllKeepsProviderContextPerPush() {
		stubBulk(emptyMap())
		val counting = object : EnrichmentProvider {
			override val name = "counter"

			override fun annotate(envelope: ObservationEnvelope, ctx: MutableMap<String, Any?>): Map<String, Any?> {
				val seen = (ctx["counter.seen"] as Int? ?: 0) + 1
				ctx["counter.seen"] = seen
				return mapOf("seen" to seen)
			}
		}

		val enriched = enricher(listOf(counting)).enrichAll(listOf(listOf(batch(installationId, installationId)), listOf(batch(installationId))))

		assertThat(enriched.map { push -> push.single().events.map { it.org.enrichmentJson } }).containsExactly(
			listOf("""{"counter":{"seen":1},"org":{"team_ids":[]}}""", """{"counter":{"seen":2},"org":{"team_ids":[]}}"""),
			listOf("""{"counter":{"seen":1},"org":{"team_ids":[]}}"""),
		)
	}

	// ── 오류 분류 ─────────────────────────────────────────────────────────

	/** 같은 mock 을 거듭 스텁하므로 호출하지 않는 꼴(`willThrow … given`)로 건다. */
	private fun failingMemberLookup(failure: RuntimeException) {
		willThrow(failure).given(installations).findMemberIdById(installationId)
	}

	@Test
	@DisplayName("커넥션 장애·트랜잭션 시작 실패는 일시 장애다")
	fun connectionFailuresAreUnavailable() {
		for (failure in listOf(DataAccessResourceFailureException("refused"), CannotCreateTransactionException("no connection"))) {
			failingMemberLookup(failure)

			assertThatThrownBy { enricher().enrich(batch(installationId)) }
				.isInstanceOf(EnrichmentUnavailableException::class.java)
				.hasMessageContaining("rds unreachable")
		}
	}

	@Test
	@DisplayName("실행 중 끊김(statement_timeout·락 경합)과 복구 가능 실패는 일시 장애다 — 소속 조회에서 나도 같다")
	fun transientFailuresAreUnavailable() {
		given(installations.findMemberIdById(installationId)).willReturn(memberId)
		for (failure in listOf(
			QueryTimeoutException("statement timeout"),
			PessimisticLockingFailureException("lock"),
			RecoverableDataAccessException("closed"),
		)) {
			willThrow(failure).given(memberships).findAllByMemberId(memberId)

			assertThatThrownBy { enricher().enrich(batch(installationId)) }
				.isInstanceOf(EnrichmentUnavailableException::class.java)
				.hasMessageContaining("rds transient failure")
		}
	}

	@Test
	@DisplayName("스키마 드리프트·제약 위반 같은 영구 오류는 그대로 전파한다")
	fun permanentFailuresPropagate() {
		failingMemberLookup(InvalidDataAccessResourceUsageException("relation does not exist"))
		assertThatThrownBy { enricher().enrich(batch(installationId)) }
			.isInstanceOf(InvalidDataAccessResourceUsageException::class.java)

		failingMemberLookup(DataIntegrityViolationException("check constraint"))
		assertThatThrownBy { enricher().enrich(batch(installationId)) }
			.isInstanceOf(DataIntegrityViolationException::class.java)
			.isNotInstanceOf(EnrichmentUnavailableException::class.java)
	}

	@Test
	@DisplayName("enrichAll 의 오류 분류는 enrich 와 같다 — 구성원·소속 묶음 조회 어느 쪽에서 나도 일시 장애만 감싸고 영구 오류는 그대로다")
	fun enrichAllClassifiesFailuresLikeEnrich() {
		val pushes = listOf(listOf(batch(installationId)))
		val transient = listOf(
			DataAccessResourceFailureException("refused") to "rds unreachable",
			CannotCreateTransactionException("no connection") to "rds unreachable",
			QueryTimeoutException("statement timeout") to "rds transient failure",
			PessimisticLockingFailureException("lock") to "rds transient failure",
			RecoverableDataAccessException("closed") to "rds transient failure",
		)
		for ((failure, message) in transient) {
			willThrow(failure).given(installations).findMemberIdsByIds(anyCollection())
			assertThatThrownBy { enricher().enrichAll(pushes) }
				.isInstanceOf(EnrichmentUnavailableException::class.java)
				.hasMessageContaining(message)
		}
		for ((failure, message) in transient) {
			stubBulk(mapOf(installationId to memberId))
			willThrow(failure).given(memberships).findAllByMemberIdIn(anyCollection())
			assertThatThrownBy { enricher().enrichAll(pushes) }
				.isInstanceOf(EnrichmentUnavailableException::class.java)
				.hasMessageContaining(message)
		}

		for (failure in listOf(InvalidDataAccessResourceUsageException("relation does not exist"), DataIntegrityViolationException("check constraint"))) {
			willThrow(failure).given(installations).findMemberIdsByIds(anyCollection())
			assertThatThrownBy { enricher().enrichAll(pushes) }.isSameAs(failure)
		}
		stubBulk(mapOf(installationId to memberId))
		val drift = InvalidDataAccessResourceUsageException("column gone")
		willThrow(drift).given(memberships).findAllByMemberIdIn(anyCollection())
		assertThatThrownBy { enricher().enrichAll(pushes) }.isSameAs(drift)
	}

	// ── provider 목록 ──────────────────────────────────────────────────────

	@Test
	@DisplayName("org 항목은 이 클래스가 쓴다 — 목록에 org provider 를 넣으면 거부한다")
	fun orgProviderIsRejected() {
		val org = object : EnrichmentProvider {
			override val name = ObservationEnricher.ORG
		}

		assertThatThrownBy { enricher(listOf(org)) }
			.isInstanceOf(IllegalArgumentException::class.java)
	}

	@Test
	@DisplayName("provider 이름이 겹치면 거부한다 — 한 항목이 조용히 덮인다")
	fun duplicateProviderNamesAreRejected() {
		assertThatThrownBy { enricher(listOf(GithubProvider(), GithubProvider())) }
			.isInstanceOf(IllegalArgumentException::class.java)
	}

	@Test
	@DisplayName("provider 주석은 자기 이름 항목이 되고 push 문맥을 공유한다")
	fun providerAnnotationsAreWritten() {
		val counting = object : EnrichmentProvider {
			override val name = "counter"

			override fun annotate(envelope: ObservationEnvelope, ctx: MutableMap<String, Any?>): Map<String, Any?> {
				val seen = (ctx["counter.seen"] as Int? ?: 0) + 1
				ctx["counter.seen"] = seen
				return mapOf("seen" to seen, "product" to envelope.product.wire)
			}
		}

		val enriched = enricher(listOf(counting)).enrich(batch(installationId, installationId)).events

		assertThat(enriched.map { it.org.enrichmentJson }).containsExactly(
			"""{"counter":{"product":"claude_code","seen":1},"org":{"team_ids":[]}}""",
			"""{"counter":{"product":"claude_code","seen":2},"org":{"team_ids":[]}}""",
		)
	}
}
