package com.team376.pulsemetry.persistence.telemetryops

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * tenant 생애 요약의 갱신 규칙(ADR 0021 §2)을 실제 PostgreSQL 위에서 본다. 기대값은 그 규칙에서 온다 — 최초 수신은 live
 * 수신 시각의 최솟값, 마지막 수신은 최댓값, 최초 관측은 유효 `source_time` 의 최솟값이고, 재처리는 아무 시각도 움직이지
 * 않는다. NULL 은 기존 시각을 지우지 못한다.
 */
@Testcontainers
class TenantIngestSummaryStoreTest {

	private val dataSource = PGSimpleDataSource().apply {
		setURL(postgres.jdbcUrl)
		user = postgres.username
		password = postgres.password
	}

	private val store = TenantIngestSummaryStore(dataSource)
	private val tenant: UUID = UUID.randomUUID()

	private val t0 = Instant.parse("2026-01-01T00:00:00Z")

	@BeforeEach
	fun setUp() {
		TelemetryOpsSchemaMigrator(dataSource).migrate()
	}

	private fun at(seconds: Long): Instant = t0.plusSeconds(seconds)

	private fun live(receivedAt: Instant, observed: Instant?) = store.record(tenant, SummaryOrigin.LIVE, receivedAt, observed)

	@Test
	@DisplayName("첫 live 수신이 행을 만든다 — 최초·마지막 수신은 수신 시각, 최초 관측은 유효 source_time 최솟값")
	fun firstLiveReceiptCreatesTheRow() {
		live(at(10), at(-3600))

		assertThat(store.find(tenant)).isEqualTo(TenantIngestSummary(tenant, at(10), at(-3600), at(10), hasPreLedgerHistory = false))
	}

	@Test
	@DisplayName("사례 25 — source_time 을 얻지 못한 push 만 받은 조직: 수신 시각은 있고 최초 관측은 null")
	fun receiptsWithoutSourceTime() {
		live(at(10), null)
		live(at(20), null)

		assertThat(store.find(tenant)).isEqualTo(TenantIngestSummary(tenant, at(10), null, at(20), hasPreLedgerHistory = false))
	}

	@Test
	@DisplayName("NULL 은 기존 시각을 지우지 못한다 — 시각 없는 push 가 뒤에 와도 최초 관측이 남는다")
	fun nullNeverWins() {
		live(at(10), at(5))
		live(at(20), null)

		assertThat(store.find(tenant)!!.firstObservedAt).isEqualTo(at(5))
	}

	@Test
	@DisplayName("사례 26·AC6 — 과거 source_time 의 지연 도착은 최초 관측만 앞당기고, 수신 시각은 순서와 무관하게 MIN·MAX 다")
	fun lateArrivalsOnlyMoveFirstObservedEarlier() {
		live(at(100), at(90))
		live(at(50), at(80)) // 수신 순서가 뒤바뀐 요청
		live(at(200), at(-86_400)) // 하루 전 관측이 늦게 도착

		assertThat(store.find(tenant)).isEqualTo(TenantIngestSummary(tenant, at(50), at(-86_400), at(200), hasPreLedgerHistory = false))
	}

	@Test
	@DisplayName("사례 26·AC6 — 같은 receipt 의 저장 재시도는 결과가 같다, 아카이브 재처리는 아무 시각도 움직이지 않는다")
	fun retriesAndReplaysDoNotCountAsNewReceipts() {
		live(at(100), at(90))
		live(at(100), at(90))
		val replayed = store.record(tenant, SummaryOrigin.REPLAY, at(10_000), at(-10_000))

		assertThat(replayed).isFalse()
		assertThat(store.find(tenant)).isEqualTo(TenantIngestSummary(tenant, at(100), at(90), at(100), hasPreLedgerHistory = false))
		// 요약이 없는 tenant 의 재처리도 행을 만들지 않는다 — 수신 이력이 아니다.
		val other = UUID.randomUUID()
		store.record(other, SummaryOrigin.REPLAY, at(1), at(1))
		assertThat(store.find(other)).isNull()
	}

	@Test
	@DisplayName("누락 요약 복구는 원래 receipt 의 수신 시각을 쓴다 — 이미 있는 값보다 늦으면 최초를 뒤로 옮기지 못한다")
	fun recoveryUsesOriginalReceiptTimes() {
		store.record(tenant, SummaryOrigin.RECOVERY, at(-500), at(-600))
		live(at(100), at(90))
		store.record(tenant, SummaryOrigin.RECOVERY, at(50), at(40))

		assertThat(store.find(tenant)).isEqualTo(TenantIngestSummary(tenant, at(-500), at(-600), at(100), hasPreLedgerHistory = false))
	}

	@Test
	@DisplayName("사례 26 — 병렬 upsert 가 최초를 뒤로, 마지막을 앞으로 옮기지 못한다")
	fun concurrentUpsertsKeepMinAndMax() {
		val receipts = (1..200L).map { at(it) to at(it - 1_000) }.shuffled(java.util.Random(7))
		val pool = Executors.newFixedThreadPool(16)
		val start = CountDownLatch(1)
		val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
		for ((receivedAt, observed) in receipts) {
			pool.execute {
				start.await()
				runCatching { live(receivedAt, observed) }.onFailure { failures.add(it) }
			}
		}
		start.countDown()
		pool.shutdown()
		assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue()

		assertThat(failures).isEmpty()
		assertThat(store.find(tenant)).isEqualTo(TenantIngestSummary(tenant, at(1), at(1 - 1_000), at(200), hasPreLedgerHistory = false))
	}

	@Test
	@DisplayName("사례 24 — 요약은 ledger·분석 테이블과 독립이다: 요약 행만으로 최초·마지막 수신과 최초 관측이 남는다")
	fun summaryStandsAlone() {
		live(at(10), at(1))
		// 이 저장소는 ledger 와 분석 테이블을 읽지도 쓰지도 않는다 — 그쪽의 보존 정리가 요약을 초기화할 경로가 없다.
		// 같은 tenant 의 요약을 다시 읽으면 처음 쓴 값 그대로다.
		assertThat(TenantIngestSummaryStore(dataSource).find(tenant)).isEqualTo(TenantIngestSummary(tenant, at(10), at(1), at(10), false))
		assertThat(rows("SELECT table_name FROM information_schema.tables WHERE table_schema = 'telemetry_ops' AND table_name LIKE '%ledger%'"))
			.isEmpty()
	}

	@Test
	@DisplayName("시각은 마이크로초로 내린다 — 컬럼 정밀도다")
	fun timesAreFlooredToMicroseconds() {
		live(Instant.parse("2026-01-01T00:00:00.123456789Z"), Instant.parse("2025-12-31T23:59:59.999999999Z"))

		val summary = store.find(tenant)!!
		assertThat(summary.firstReceivedAt).isEqualTo(Instant.parse("2026-01-01T00:00:00.123456Z"))
		assertThat(summary.firstObservedAt).isEqualTo(Instant.parse("2025-12-31T23:59:59.999999Z"))
	}

	@Test
	@DisplayName("연결 실패는 일시 장애다 — 앱이 503 으로 돌린다")
	fun connectionFailureIsUnavailable() {
		val unreachable = PGSimpleDataSource().apply {
			setURL("jdbc:postgresql://127.0.0.1:1/none")
			connectTimeout = 1
		}

		assertThatThrownBy { TenantIngestSummaryStore(unreachable).record(tenant, SummaryOrigin.LIVE, at(1), null) }
			.isInstanceOf(TelemetryOpsUnavailableException::class.java)
	}

	private fun rows(sql: String): List<String> =
		dataSource.connection.use { connection ->
			connection.createStatement().use { statement ->
				statement.executeQuery(sql).use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
			}
		}

	companion object {
		@Container
		@JvmStatic
		val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:16-alpine")
	}
}
