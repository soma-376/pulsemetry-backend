package com.team376.pulsemetry.persistence.telemetryops

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.util.UUID

/**
 * 요약 도입 전 이력의 백필(ADR 0021 §2). 원천에 행이 있는 tenant 를 표시하되 시각을 지어내지 않고, 완료를 기록하며,
 * 다시 돌려도 원천을 다시 읽지 않는다.
 */
@Testcontainers
class TenantSummaryBackfillTest {

	private val dataSource = PGSimpleDataSource().apply {
		setURL(postgres.jdbcUrl)
		user = postgres.username
		password = postgres.password
	}

	private val backfill = TenantSummaryBackfill(dataSource)
	private val summaries = TenantIngestSummaryStore(dataSource)

	/** 테스트마다 다른 백필 이름을 쓴다 — 컨테이너를 공유한다. */
	private val name = "test-${UUID.randomUUID()}"

	@BeforeEach
	fun setUp() {
		prepareEnrollmentSchema(dataSource)
		TelemetryOpsSchemaMigrator(dataSource).migrate()
	}

	@Test
	@DisplayName("이력이 있는 tenant 를 표시한다 — 새 요약 행의 시각은 NULL, 이미 있는 행의 시각은 그대로")
	fun marksTenantsWithoutInventingTimes() {
		val known = UUID.randomUUID()
		val fresh = UUID.randomUUID()
		val receivedAt = Instant.parse("2026-01-01T00:00:00Z")
		summaries.record(known, SummaryOrigin.LIVE, receivedAt, null)

		val outcome = backfill.run(name, "enriched_events") { listOf(known.toString(), fresh.toString(), fresh.toString()) }

		assertThat(outcome.executed).isTrue()
		assertThat(outcome.completion.tenantsMarked).isEqualTo(2)
		assertThat(outcome.completion.source).isEqualTo("enriched_events")
		assertThat(summaries.find(fresh)).isEqualTo(TenantIngestSummary(fresh, null, null, null, hasPreLedgerHistory = true))
		assertThat(summaries.find(known)).isEqualTo(TenantIngestSummary(known, receivedAt, null, receivedAt, hasPreLedgerHistory = true))
		assertThat(backfill.completion(name)).isEqualTo(outcome.completion)
	}

	@Test
	@DisplayName("원천에 tenant 가 없어도 완료를 기록한다 — 표시 0")
	fun emptySourceStillCompletes() {
		val outcome = backfill.run(name, "enriched_events") { emptyList() }

		assertThat(outcome.completion.tenantsMarked).isZero()
		assertThat(backfill.completion(name)).isNotNull()
	}

	@Test
	@DisplayName("다시 돌리면 원천을 읽지 않고 같은 완료 기록을 돌려준다 — 멱등")
	fun rerunIsIdempotent() {
		val tenant = UUID.randomUUID()
		val first = backfill.run(name, "enriched_events") { listOf(tenant.toString()) }

		val second = backfill.run(name, "enriched_events") { error("완료된 백필이 원천을 다시 읽었다") }

		assertThat(second.executed).isFalse()
		assertThat(second.completion).isEqualTo(first.completion)
		assertThat(summaries.find(tenant)!!.hasPreLedgerHistory).isTrue()
	}

	@Test
	@DisplayName("완료 기록이 없으면 백필은 끝나지 않은 것이다 — 요약 부재를 수집한 적 없음으로 해석할 근거가 없다")
	fun noCompletionMeansNotDone() {
		assertThat(backfill.completion(name)).isNull()
	}

	@Test
	@DisplayName("UUID 가 아닌 원천 값은 요약 행이 될 수 없어 건너뛰고 알린다")
	fun nonUuidTenantsAreSkipped() {
		val tenant = UUID.randomUUID()

		val outcome = backfill.run(name, "enriched_events") { listOf(tenant.toString(), "(unknown)", tenant.toString().uppercase()) }

		assertThat(outcome.completion.tenantsMarked).isEqualTo(1)
		assertThat(outcome.skippedTenantIds).containsExactlyInAnyOrder("(unknown)", tenant.toString().uppercase())
	}

	@Test
	@DisplayName("백필 뒤의 live 수신은 시각을 채우고 표시는 남는다")
	fun liveAfterBackfillKeepsTheFlag() {
		val tenant = UUID.randomUUID()
		backfill.run(name, "enriched_events") { listOf(tenant.toString()) }
		val receivedAt = Instant.parse("2026-02-01T00:00:00Z")

		summaries.record(tenant, SummaryOrigin.LIVE, receivedAt, receivedAt.minusSeconds(60))

		assertThat(summaries.find(tenant))
			.isEqualTo(TenantIngestSummary(tenant, receivedAt, receivedAt.minusSeconds(60), receivedAt, hasPreLedgerHistory = true))
	}

	companion object {
		@Container
		@JvmStatic
		val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:16-alpine")
	}
}
