package com.team376.pulsemetry.dashboard.snapshot

import com.team376.pulsemetry.dashboard.error.DashboardException
import com.team376.pulsemetry.dashboard.error.ErrorCode
import com.team376.pulsemetry.dashboard.error.ErrorResponse
import com.team376.pulsemetry.dashboard.request.CompareMode
import com.team376.pulsemetry.dashboard.request.ComparedPeriod
import com.team376.pulsemetry.dashboard.request.DatePeriod
import com.team376.pulsemetry.dashboard.request.PageCursor
import com.team376.pulsemetry.dashboard.request.QueryReader
import com.team376.pulsemetry.dashboard.store.StoreLimitExceededException
import com.team376.pulsemetry.dashboard.store.StoreUnavailableException
import com.team376.pulsemetry.dashboard.support.DashboardTestStores
import com.team376.pulsemetry.dashboard.support.LossyHttpClient
import com.team376.pulsemetry.dashboard.support.MutableClock
import com.team376.pulsemetry.dashboard.support.SnapshotAssembly
import com.team376.pulsemetry.dashboard.support.SourceFixtures
import com.team376.pulsemetry.dashboard.support.SourceFixtures.Event
import com.team376.pulsemetry.persistence.telemetryops.TenantRetentionBoundaryStore
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * snapshot 수명 (ADR 0023 §4) — 공개 CAS, 만료, 권한 재검사, 한도, 정리. 기대값은 ADR 0023 과 허브 ADR 0007 수용 기준의 문장에서 쓴다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SnapshotLifecycleTest {

	@BeforeAll
	fun schemas() {
		DashboardTestStores.ensureSchemas()
	}

	private val week = ComparedPeriod(DatePeriod(LocalDate.parse("2026-09-07"), LocalDate.parse("2026-09-13"), QueryReader.SEOUL), CompareMode.NONE)

	private fun kst(dateTime: String) = LocalDateTime.parse(dateTime).atZone(QueryReader.SEOUL).toInstant()

	private fun seed(tenant: UUID, count: Int = 2) {
		SourceFixtures.insertEvents(tenant, *(1..count).map { Event("$tenant-$it", kst("2026-09-08T10:00:00").plusSeconds(it.toLong())) }.toTypedArray())
	}

	private fun expectExpired(block: () -> Unit) {
		assertThatThrownBy(block).isInstanceOfSatisfying(DashboardException::class.java) { assertThat(it.code).isEqualTo(ErrorCode.SNAPSHOT_EXPIRED) }
	}

	private fun status(snapshotId: String): Pair<String, String?> =
		DashboardTestStores.writer.sql("SELECT status::text, failure_reason FROM dashboard_cache.snapshots WHERE snapshot_id = :s")
			.param("s", snapshotId).query { rs, _ -> rs.getString(1) to rs.getString(2) }.single()

	private fun statusesOf(tenant: UUID): List<Pair<String, String?>> =
		DashboardTestStores.writer.sql("SELECT status::text, failure_reason FROM dashboard_cache.snapshots WHERE tenant_id = :t ORDER BY created_at")
			.param("t", tenant).query { rs, _ -> rs.getString(1) to rs.getString(2) }.list()

	private fun physicalUsageRows(buildId: UUID): Long =
		DashboardTestStores.clickHouseAdmin("SELECT count() FROM dashboard_cache.snapshot_usage WHERE build_id = '$buildId'").trim().toLong()

	@Test
	@DisplayName("사례 14·AC3 — 행 0개 snapshot 은 ready 이고 다시 읽어도 유효하다")
	fun emptySnapshotIsReady() {
		val assembly = SnapshotAssembly()
		val tenant = UUID.randomUUID()

		val ready = assembly.service.create(tenant, UUID.randomUUID(), week)

		assertThat(ready.status).isEqualTo("ready")
		assertThat(ready.usageRows).isZero()
		assertThat(ready.observedDayRows).isZero()
		assertThat(ready.expiresAt).isEqualTo(ready.readyAt!!.plus(Duration.ofMinutes(10)))
		assertThat(assembly.service.requireReady(ready.snapshotId, tenant).buildId).isEqualTo(ready.buildId)
		assertThat(assembly.service.requireReady(ready.snapshotId, tenant).buildId).isEqualTo(ready.buildId)
	}

	@Test
	@DisplayName("사례 19·AC4 — 10분이 지나면 물리 행이 남아 있어도 409, 그 전에는 다른 인스턴스도 같은 snapshot 을 읽는다")
	fun expiryIsDecidedByManifest() {
		val clock = MutableClock()
		val tenant = UUID.randomUUID()
		seed(tenant)
		val ready = SnapshotAssembly(clock = clock).service.create(tenant, UUID.randomUUID(), week)

		// 새로 조립한 부품 = 재시작했거나 다른 인스턴스다. 상태는 RDS manifest 에만 있다.
		clock.advance(Duration.ofMinutes(9).plusSeconds(59))
		assertThat(SnapshotAssembly(clock = clock).service.requireReady(ready.snapshotId, tenant).usageRows).isEqualTo(2)

		clock.advance(Duration.ofSeconds(1))
		expectExpired { SnapshotAssembly(clock = clock).service.requireReady(ready.snapshotId, tenant) }
		assertThat(physicalUsageRows(ready.buildId)).isEqualTo(2)

		// 유효기간 중에 TTL 이 행을 먼저 지우지 않는다 — 물리 정리 시각이 API 만료보다 늦다.
		val purgeAfter = DashboardTestStores.clickHouseAdmin(
			"SELECT toUnixTimestamp(min(purge_after)) FROM dashboard_cache.snapshot_usage WHERE build_id = '${ready.buildId}'",
		).trim().toLong()
		assertThat(purgeAfter).isGreaterThan(ready.expiresAt!!.epochSecond)
	}

	@Test
	@DisplayName("사례 20·AC5 — 다른 tenant 의 snapshot ID 는 409, 다른 snapshot·목록의 cursor 는 400, 형식이 틀린 ID 는 409")
	fun snapshotIsNotACredential() {
		val assembly = SnapshotAssembly()
		val tenant = UUID.randomUUID()
		val ready = assembly.service.create(tenant, UUID.randomUUID(), week)

		expectExpired { assembly.service.requireReady(ready.snapshotId, UUID.randomUUID()) }
		expectExpired { assembly.service.requireReady("not-a-snapshot", tenant) }
		expectExpired { assembly.service.requireReady(SnapshotIds.next(), tenant) }

		assembly.service.requireCursor(PageCursor(ready.snapshotId, "teams:cost", listOf("1")), ready.snapshotId, "teams:cost")
		for (cursor in listOf(PageCursor(SnapshotIds.next(), "teams:cost", emptyList()), PageCursor(ready.snapshotId, "teams:token", emptyList()))) {
			assertThatThrownBy { assembly.service.requireCursor(cursor, ready.snapshotId, "teams:cost") }
				.isInstanceOfSatisfying(DashboardException::class.java) {
					assertThat(it.fieldErrors).containsExactly(ErrorResponse.FieldError("cursor", "invalid_cursor"))
				}
		}
	}

	@Test
	@DisplayName("무효화한 snapshot 과 삭제 정책 epoch 가 바뀐 snapshot 은 409 다")
	fun invalidationAndEpochChange() {
		val assembly = SnapshotAssembly()
		val tenant = UUID.randomUUID()
		val first = assembly.service.create(tenant, UUID.randomUUID(), week)
		val second = assembly.service.create(tenant, UUID.randomUUID(), week)

		assertThat(assembly.service.invalidateTenant(tenant, "permission_changed")).isEqualTo(2)
		expectExpired { assembly.service.requireReady(first.snapshotId, tenant) }

		val other = UUID.randomUUID()
		val ready = assembly.service.create(other, UUID.randomUUID(), week)
		SourceFixtures.setBoundary(other, kst("2026-01-01T00:00:00"), policyEpoch = 1)
		expectExpired { assembly.service.requireReady(ready.snapshotId, other) }
		assertThat(second.status).isEqualTo("ready")
	}

	@Test
	@DisplayName("사례 17·AC3 — 복사가 서버에서 끝났는데 응답을 잃으면 failed, 새 ID 재시도에는 옛 행이 섞이지 않는다")
	fun lostResponseIsNotPublished() {
		val tenant = UUID.randomUUID()
		seed(tenant, count = 3)
		val lossy = LossyHttpClient(matches = { it.uri().rawQuery.contains("param_current_from") })

		assertThatThrownBy { SnapshotAssembly(httpClient = lossy).service.create(tenant, UUID.randomUUID(), week) }
			.isInstanceOf(StoreUnavailableException::class.java)
		val failed = DashboardTestStores.writer.sql("SELECT snapshot_id, build_id FROM dashboard_cache.snapshots WHERE tenant_id = :t")
			.param("t", tenant).query { rs, _ -> rs.getString(1) to rs.getObject(2, UUID::class.java) }.single()
		assertThat(status(failed.first)).isEqualTo("failed" to "store_unavailable")
		// 서버에는 행이 남았다 — 공개되지 않았을 뿐이다.
		assertThat(physicalUsageRows(failed.second)).isEqualTo(3)
		expectExpired { SnapshotAssembly().service.requireReady(failed.first, tenant) }

		val retried = SnapshotAssembly().service.create(tenant, UUID.randomUUID(), week)
		assertThat(retried.snapshotId).isNotEqualTo(failed.first)
		assertThat(retried.usageRows).isEqualTo(3)
		assertThat(physicalUsageRows(retried.buildId)).isEqualTo(3)
	}

	@Test
	@DisplayName("AC10 — build 중 삭제 정책 epoch 가 바뀌면 공개 CAS 가 실패하고 snapshot 은 공개되지 않는다")
	fun epochChangeDuringBuildFailsPublish() {
		val tenant = UUID.randomUUID()
		seed(tenant)
		val hook = LossyHttpClient(
			matches = { it.uri().rawQuery.contains("param_current_from") },
			loseResponse = false,
			onMatched = { SourceFixtures.setBoundary(tenant, kst("2026-01-01T00:00:00"), policyEpoch = 7) },
		)

		assertThatThrownBy { SnapshotAssembly(httpClient = hook).service.create(tenant, UUID.randomUUID(), week) }
			.isInstanceOfSatisfying(SnapshotUnavailableException::class.java) { assertThat(it.reason).isEqualTo("publish_rejected") }
		assertThat(statusesOf(tenant)).containsExactly("failed" to "publish_rejected")

		// 새 build 는 새 epoch 로 시작해 공개된다.
		assertThat(SnapshotAssembly().service.create(tenant, UUID.randomUUID(), week).policyEpoch).isEqualTo(7)
	}

	@Test
	@DisplayName("사례 27 — 보존 작업의 경계 발효(advance)만으로 ready snapshot 은 409, 진행 중 build 는 공개 CAS 실패다 — 캐시에 쓰지 않는다")
	fun retentionAdvanceInvalidatesWithoutTouchingTheCache() {
		val boundaries = TenantRetentionBoundaryStore(
			DriverManagerDataSource(DashboardTestStores.postgres.jdbcUrl, DashboardTestStores.postgres.username, DashboardTestStores.postgres.password),
		)
		val tenant = UUID.randomUUID()
		seed(tenant)
		val assembly = SnapshotAssembly()
		val ready = assembly.service.create(tenant, UUID.randomUUID(), week)
		val hook = LossyHttpClient(
			matches = { it.uri().rawQuery.contains("param_current_from") },
			loseResponse = false,
			onMatched = { boundaries.advance(tenant, kst("2026-01-01T00:00:00")) },
		)

		assertThatThrownBy { SnapshotAssembly(httpClient = hook).service.create(tenant, UUID.randomUUID(), week) }
			.isInstanceOfSatisfying(SnapshotUnavailableException::class.java) { assertThat(it.reason).isEqualTo("publish_rejected") }
		expectExpired { assembly.service.requireReady(ready.snapshotId, tenant) }
		// 보존 작업은 manifest 를 무효화하지 않았다 — epoch 비교가 무효화다.
		val invalidated = DashboardTestStores.writer.sql("SELECT count(*) FROM dashboard_cache.snapshots WHERE tenant_id = :t AND invalidated_at IS NOT NULL")
			.param("t", tenant).query(Long::class.java).single()
		assertThat(invalidated).isZero()

		// 같은 경계로 다시 발효해도 epoch 는 그대로 — 새 epoch 로 만든 snapshot 은 계속 읽힌다.
		val fresh = SnapshotAssembly().service.create(tenant, UUID.randomUUID(), week)
		boundaries.advance(tenant, kst("2026-01-01T00:00:00"))
		assertThat(assembly.service.requireReady(fresh.snapshotId, tenant).policyEpoch).isEqualTo(1)
	}

	@Test
	@DisplayName("build 중 무효화되거나 마감이 지나면 공개 CAS 가 실패한다")
	fun invalidationOrDeadlineDuringBuildFailsPublish() {
		val invalidated = UUID.randomUUID()
		val assembly = SnapshotAssembly()
		val hook = LossyHttpClient(
			matches = { it.uri().rawQuery.contains("param_current_from") },
			loseResponse = false,
			onMatched = { assembly.service.invalidateTenant(invalidated, "permission_changed") },
		)
		assertThatThrownBy { SnapshotAssembly(httpClient = hook).service.create(invalidated, UUID.randomUUID(), week) }
			.isInstanceOf(SnapshotUnavailableException::class.java)
		assertThat(statusesOf(invalidated)).containsExactly("failed" to "publish_rejected")

		val late = UUID.randomUUID()
		val clock = MutableClock()
		val slow = LossyHttpClient(
			matches = { it.uri().rawQuery.contains("param_current_from") },
			loseResponse = false,
			onMatched = { clock.advance(Duration.ofSeconds(61)) },
		)
		assertThatThrownBy { SnapshotAssembly(clock = clock, httpClient = slow).service.create(late, UUID.randomUUID(), week) }
			.isInstanceOf(SnapshotUnavailableException::class.java)
		assertThat(statusesOf(late)).containsExactly("failed" to "publish_rejected")
	}

	@Test
	@DisplayName("사례 30 — 복사 행 한도를 넘으면 잘라 내지 않고 실패하고 ready 는 없다")
	fun copyLimitFailsWithoutPartialResult() {
		val tenant = UUID.randomUUID()
		seed(tenant, count = 5)
		val assembly = SnapshotAssembly(limits = SnapshotAssembly.DEFAULT_LIMITS.copy(maxCopyRows = 1))

		assertThatThrownBy { assembly.service.create(tenant, UUID.randomUUID(), week) }.isInstanceOf(StoreLimitExceededException::class.java)
		assertThat(statusesOf(tenant)).containsExactly("failed" to "limit_exceeded")
	}

	@Test
	@DisplayName("사례 30 — tenant 의 진행 중 build 가 한도에 차 있으면 새 build 를 시작하지 않고, 마감이 지난 build 는 세지 않는다")
	fun concurrencyLimit() {
		val clock = MutableClock()
		val tenant = UUID.randomUUID()
		val assembly = SnapshotAssembly(clock = clock, limits = SnapshotAssembly.DEFAULT_LIMITS.copy(maxConcurrentBuilds = 1))
		// 진행 중인 build 하나를 직접 세운다.
		assembly.manifests.insertBuilding(
			SnapshotManifestStore.NewManifest(
				SnapshotIds.next(), UUID.randomUUID(), tenant, UUID.randomUUID(), week.current, CompareMode.NONE, null,
				clock.instant(), null, 0, "model-id-v1", clock.instant(), clock.instant().plusSeconds(60),
			),
			maxConcurrentBuilds = 1,
		)

		assertThatThrownBy { assembly.service.create(tenant, UUID.randomUUID(), week) }
			.isInstanceOfSatisfying(SnapshotUnavailableException::class.java) { assertThat(it.reason).isEqualTo("concurrency_limit") }
		assertThat(statusesOf(tenant)).hasSize(1)

		clock.advance(Duration.ofSeconds(61))
		assertThat(assembly.service.create(tenant, UUID.randomUUID(), week).status).isEqualTo("ready")
	}

	@Test
	@DisplayName("obtain — ID 가 없으면 만들고, 있으면 같은 범위일 때만 재사용한다. 비교가 없는 화면은 비교 방식을 따지지 않는다")
	fun obtainReusesOnlyTheSameScope() {
		val assembly = SnapshotAssembly()
		val tenant = UUID.randomUUID()
		val compared = week.copy(mode = CompareMode.PREV_WEEK)

		val created = assembly.service.obtain(tenant, UUID.randomUUID(), compared, usesComparison = true, snapshotId = null)
		assertThat(assembly.service.obtain(tenant, UUID.randomUUID(), compared, true, created.snapshotId).snapshotId).isEqualTo(created.snapshotId)
		assertThat(assembly.service.obtain(tenant, UUID.randomUUID(), week, false, created.snapshotId).snapshotId).isEqualTo(created.snapshotId)

		expectExpired { assembly.service.obtain(tenant, UUID.randomUUID(), week, true, created.snapshotId) }
		val otherDates = ComparedPeriod(DatePeriod(LocalDate.parse("2026-09-07"), LocalDate.parse("2026-09-14"), QueryReader.SEOUL), CompareMode.PREV_WEEK)
		expectExpired { assembly.service.obtain(tenant, UUID.randomUUID(), otherDates, true, created.snapshotId) }
	}

	@Test
	@DisplayName("정리 — 마감이 지난 building 은 버려지고, 실패·물리 정리 시각이 지난 build 는 캐시 행과 manifest 가 지워진다. 유효한 것은 남는다")
	fun cleanup() {
		val clock = MutableClock()
		val tenant = UUID.randomUUID()
		seed(tenant)
		val assembly = SnapshotAssembly(clock = clock)
		val kept = assembly.service.create(tenant, UUID.randomUUID(), week)
		assertThatThrownBy {
			SnapshotAssembly(clock = clock, limits = SnapshotAssembly.DEFAULT_LIMITS.copy(maxCopyRows = 1)).service.create(tenant, UUID.randomUUID(), week)
		}.isInstanceOf(StoreLimitExceededException::class.java)
		val overdue = SnapshotIds.next()
		assembly.manifests.insertBuilding(
			SnapshotManifestStore.NewManifest(
				overdue, UUID.randomUUID(), tenant, UUID.randomUUID(), week.current, CompareMode.NONE, null,
				clock.instant(), null, 0, "model-id-v1", clock.instant(), clock.instant().plusSeconds(1),
			),
			maxConcurrentBuilds = 10,
		)
		clock.advance(Duration.ofSeconds(2))

		assembly.cleaner.run()

		assertThat(statusesOf(tenant)).containsExactly("ready" to null)
		assertThat(physicalUsageRows(kept.buildId)).isEqualTo(2)

		// 물리 정리 시각(시작 + 60초 + 10분 + 60초)이 지나면 ready 였던 것도 지운다.
		clock.advance(Duration.ofMinutes(13))
		assembly.cleaner.run()
		assertThat(statusesOf(tenant)).isEmpty()
		assertThat(physicalUsageRows(kept.buildId)).isZero()
	}
}
