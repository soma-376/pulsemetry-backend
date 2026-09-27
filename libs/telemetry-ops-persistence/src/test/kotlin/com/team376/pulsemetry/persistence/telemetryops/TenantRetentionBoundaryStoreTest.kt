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
 * 삭제 경계의 갱신 규칙(ADR 0024 §1)을 실제 PostgreSQL 위에서 본다. 기대값은 그 규칙에서 온다 — 행이 없으면 경계 없음·epoch 0,
 * 늦은 후보만 경계를 옮기고 epoch 를 1 올리며, 같거나 이른 후보는 아무것도 바꾸지 않는다.
 */
@Testcontainers
class TenantRetentionBoundaryStoreTest {

	private val dataSource = PGSimpleDataSource().apply {
		setURL(postgres.jdbcUrl)
		user = postgres.username
		password = postgres.password
	}

	private val store = TenantRetentionBoundaryStore(dataSource)
	private val tenant: UUID = UUID.randomUUID()

	/** 2025-09-24 KST 00:00. */
	private val sep24 = Instant.parse("2025-09-23T15:00:00Z")

	@BeforeEach
	fun setUp() {
		TelemetryOpsSchemaMigrator(dataSource).migrate()
	}

	@Test
	@DisplayName("행이 없으면 경계가 없고 epoch 는 0 이다")
	fun absentRowIsNoBoundary() {
		assertThat(store.read(tenant)).isEqualTo(TenantRetentionBoundary(tenant, null, 0))
	}

	@Test
	@DisplayName("첫 발효는 epoch 1 이고, 늦은 후보마다 경계를 옮기며 epoch 가 1 씩 오른다")
	fun laterCandidatesMoveTheBoundary() {
		val first = store.advance(tenant, sep24)
		assertThat(first).isEqualTo(TenantRetentionBoundaryStore.Advance(TenantRetentionBoundary(tenant, sep24, 1), moved = true))

		val later = sep24.plusSeconds(86_400)
		val second = store.advance(tenant, later)
		assertThat(second).isEqualTo(TenantRetentionBoundaryStore.Advance(TenantRetentionBoundary(tenant, later, 2), moved = true))
		assertThat(store.read(tenant)).isEqualTo(TenantRetentionBoundary(tenant, later, 2))
	}

	@Test
	@DisplayName("보존 연장·재실행 — 같거나 이른 후보는 경계도 epoch 도 바꾸지 않는다")
	fun earlierOrEqualCandidatesChangeNothing() {
		store.advance(tenant, sep24)

		// 36개월로 연장한 것과 같은 이른 후보, 그리고 같은 입력의 재실행.
		val extended = store.advance(tenant, Instant.parse("2023-09-23T15:00:00Z"))
		val rerun = store.advance(tenant, sep24)

		val unchanged = TenantRetentionBoundary(tenant, sep24, 1)
		assertThat(extended).isEqualTo(TenantRetentionBoundaryStore.Advance(unchanged, moved = false))
		assertThat(rerun).isEqualTo(TenantRetentionBoundaryStore.Advance(unchanged, moved = false))
		assertThat(store.read(tenant)).isEqualTo(unchanged)
	}

	@Test
	@DisplayName("tenant 마다 따로다 — 한 조직의 경계가 다른 조직을 움직이지 않는다")
	fun boundariesArePerTenant() {
		val other = UUID.randomUUID()
		store.advance(tenant, sep24)

		assertThat(store.read(other)).isEqualTo(TenantRetentionBoundary.none(other))
	}

	@Test
	@DisplayName("마이크로초 아래 자리가 있는 후보는 거부한다 — 내리지도 올리지도 않는다")
	fun subMicrosecondCandidateIsRejected() {
		assertThatThrownBy { store.advance(tenant, sep24.plusNanos(1)) }.isInstanceOf(IllegalArgumentException::class.java)

		assertThat(store.read(tenant)).isEqualTo(TenantRetentionBoundary.none(tenant))
	}

	@Test
	@DisplayName("동시 발효 — 어떤 순서로 커밋돼도 경계는 가장 늦은 후보이고 epoch 는 실제 이동 수를 넘지 않는다")
	fun concurrentAdvancesKeepTheMaximum() {
		val candidates = (0 until 8).map { sep24.plusSeconds(it * 3_600L) }
		val start = CountDownLatch(1)
		val pool = Executors.newFixedThreadPool(candidates.size)
		try {
			val results = candidates.shuffled().map { candidate ->
				pool.submit<TenantRetentionBoundaryStore.Advance> {
					start.await()
					store.advance(tenant, candidate)
				}
			}
			start.countDown()
			val moved = results.map { it.get(30, TimeUnit.SECONDS) }.count { it.moved }

			val boundary = store.read(tenant)
			assertThat(boundary.deletedBefore).isEqualTo(candidates.last())
			assertThat(boundary.policyEpoch).isEqualTo(moved.toLong()).isBetween(1L, candidates.size.toLong())
		} finally {
			pool.shutdownNow()
		}
	}

	@Test
	@DisplayName("스키마가 아직 없으면 일시 장애다 — 경계를 읽지 못한 채 허용하지 않는다")
	fun missingSchemaIsTransient() {
		val bare = PGSimpleDataSource().apply {
			setURL(postgres.jdbcUrl.replace("/${postgres.databaseName}", "/postgres"))
			user = postgres.username
			password = postgres.password
		}

		assertThatThrownBy { TenantRetentionBoundaryStore(bare).read(tenant) }
			.isInstanceOf(TelemetryOpsUnavailableException::class.java)
	}

	companion object {
		@Container
		@JvmStatic
		val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:16-alpine")
	}
}
