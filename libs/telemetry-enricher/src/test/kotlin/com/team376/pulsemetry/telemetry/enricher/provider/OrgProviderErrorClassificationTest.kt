package com.team376.pulsemetry.telemetry.enricher.provider

import com.team376.pulsemetry.persistence.enrollment.repository.TeamMembershipRepository
import com.team376.pulsemetry.telemetry.enricher.Enriched
import com.team376.pulsemetry.telemetry.enricher.EnrichmentUnavailableException
import com.team376.pulsemetry.telemetry.enricher.support.TestEvents
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
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
 * 오류 분류를 좁게 고정한다. **넓히지 마라.**
 *
 * 일시 장애만 [EnrichmentUnavailableException] 이고, 앱이 그것을 503 으로 돌려 데몬이
 * 재전송하게 한다. 스키마 드리프트 같은 **영구 오류를 여기 넣으면 그 오류가 드러나지 않는다.**
 *
 * 적재 단계도 같은 원칙이다(허브 ADR 0006) — `ClickHouseErrorClassificationTest` 와 나란히 읽는다.
 */
class OrgProviderErrorClassificationTest {

	private val installationId: UUID = UUID.randomUUID()
	private val item = Enriched(TestEvents.log(installationId, Instant.parse("2026-06-01T00:00:00Z")))

	private fun providerThrowing(failure: RuntimeException): OrgProvider {
		val repository = mock(TeamMembershipRepository::class.java)
		given(repository.findActiveTeamMembershipsByInstallationId(installationId)).willThrow(failure)
		return OrgProvider(repository)
	}

	@Test
	@DisplayName("커넥션 장애는 일시 장애다 — 앱이 503 으로 돌린다")
	fun connectionFailureBecomesUnavailable() {
		val provider = providerThrowing(DataAccessResourceFailureException("connection refused"))

		assertThatThrownBy { provider.enrich(item, HashMap()) }
			.isInstanceOf(EnrichmentUnavailableException::class.java)
			.hasMessageContaining("rds unreachable")
	}

	@Test
	@DisplayName("트랜잭션을 열지 못한 것도 커넥션 장애와 같은 사실이다")
	fun transactionStartFailureBecomesUnavailable() {
		val provider = providerThrowing(CannotCreateTransactionException("could not open connection"))

		assertThatThrownBy { provider.enrich(item, HashMap()) }
			.isInstanceOf(EnrichmentUnavailableException::class.java)
	}

	@Test
	@DisplayName("statement_timeout 은 실행 중 끊김이라 일시 장애다")
	fun queryTimeoutBecomesUnavailable() {
		val provider = providerThrowing(QueryTimeoutException("canceling statement due to statement timeout"))

		assertThatThrownBy { provider.enrich(item, HashMap()) }
			.isInstanceOf(EnrichmentUnavailableException::class.java)
			.hasMessageContaining("rds transient failure")
	}

	@Test
	@DisplayName("락 경합도 일시 장애다 — TransientDataAccessException 계열 전부")
	fun lockContentionBecomesUnavailable() {
		val provider = providerThrowing(PessimisticLockingFailureException("could not obtain lock"))

		assertThatThrownBy { provider.enrich(item, HashMap()) }
			.isInstanceOf(EnrichmentUnavailableException::class.java)
	}

	@Test
	@DisplayName("드라이버가 복구 가능으로 분류한 실패도 일시 장애다")
	fun recoverableFailureBecomesUnavailable() {
		val provider = providerThrowing(RecoverableDataAccessException("connection was closed"))

		assertThatThrownBy { provider.enrich(item, HashMap()) }
			.isInstanceOf(EnrichmentUnavailableException::class.java)
	}

	@Test
	@DisplayName("스키마 드리프트는 영구 오류라 그대로 전파한다")
	fun schemaDriftPropagates() {
		val provider = providerThrowing(InvalidDataAccessResourceUsageException("relation does not exist"))

		assertThatThrownBy { provider.enrich(item, HashMap()) }
			.isInstanceOf(InvalidDataAccessResourceUsageException::class.java)
	}

	@Test
	@DisplayName("제약 위반 같은 NonTransient 영구 오류도 그대로 전파한다 — 앱이 400 으로 돌린다")
	fun otherNonTransientFailuresPropagate() {
		val provider = providerThrowing(DataIntegrityViolationException("violates check constraint"))

		assertThatThrownBy { provider.enrich(item, HashMap()) }
			.isInstanceOf(DataIntegrityViolationException::class.java)
			.isNotInstanceOf(EnrichmentUnavailableException::class.java)
	}

	@Test
	@DisplayName("installation_id 가 UUID 가 아니면 그대로 던진다 — 일시 장애가 아니라 잘못된 입력이다")
	fun malformedInstallationIdPropagates() {
		val provider = OrgProvider(mock(TeamMembershipRepository::class.java))
		val malformed = Enriched(TestEvents.logWithRawInstallationId("not-a-uuid", Instant.parse("2026-06-01T00:00:00Z")))

		assertThatThrownBy { provider.enrich(malformed, HashMap()) }
			.isInstanceOf(IllegalArgumentException::class.java)
	}
}
