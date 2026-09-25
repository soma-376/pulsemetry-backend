package com.team376.pulsemetry.telemetry.enricher.observation

import com.team376.pulsemetry.persistence.enrollment.repository.InstallationRepository
import com.team376.pulsemetry.persistence.enrollment.repository.TeamMembershipRepository
import com.team376.pulsemetry.telemetry.adapter.observation.ObservationEnvelope
import com.team376.pulsemetry.telemetry.enricher.EnrichmentUnavailableException
import com.team376.pulsemetry.telemetry.enricher.provider.EnrichmentProvider
import com.team376.pulsemetry.telemetry.enricher.provider.GithubProvider
import com.team376.pulsemetry.telemetry.enricher.provider.OrgProvider
import com.team376.pulsemetry.telemetry.enricher.support.TestObservations
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.willThrow
import org.mockito.Mockito.mock
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
import java.util.UUID

/**
 * 조회 횟수·오류 분류·provider 목록 — DB 없이 볼 수 있는 것. 오류 분류는 구 `OrgProviderErrorClassificationTest` 와
 * 같은 경계다. **넓히지 마라.** 일시 장애만 [EnrichmentUnavailableException](앱이 503)이고 영구 오류는 그대로 전파된다.
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
	@DisplayName("구성원이 없으면 소속 이력을 읽지 않는다")
	fun noMembershipLookupWithoutMember() {
		enricher().enrich(batch(installationId))

		verifyNoInteractions(memberships)
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

	// ── provider 목록 ──────────────────────────────────────────────────────

	@Test
	@DisplayName("org 항목은 이 클래스가 쓴다 — 목록에 org provider 를 넣으면 거부한다")
	fun orgProviderIsRejected() {
		assertThatThrownBy { enricher(listOf(OrgProvider(memberships))) }
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

			override fun enrich(
				item: com.team376.pulsemetry.telemetry.enricher.Enriched,
				ctx: MutableMap<String, Any?>,
			): Map<String, Any?> = emptyMap()

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
