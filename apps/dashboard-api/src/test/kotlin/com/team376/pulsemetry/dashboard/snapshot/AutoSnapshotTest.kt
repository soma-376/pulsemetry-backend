package com.team376.pulsemetry.dashboard.snapshot

import com.team376.pulsemetry.dashboard.analytics.CollectionPolicyAnnotations
import com.team376.pulsemetry.dashboard.error.DashboardException
import com.team376.pulsemetry.dashboard.error.ErrorCode
import com.team376.pulsemetry.dashboard.request.CompareMode
import com.team376.pulsemetry.dashboard.request.ComparedPeriod
import com.team376.pulsemetry.dashboard.request.DatePeriod
import com.team376.pulsemetry.dashboard.request.QueryReader
import com.team376.pulsemetry.dashboard.request.TrendBucketMode
import com.team376.pulsemetry.dashboard.support.DashboardTestStores
import com.team376.pulsemetry.dashboard.support.MutableClock
import com.team376.pulsemetry.dashboard.support.SnapshotAssembly
import com.team376.pulsemetry.dashboard.support.SourceFixtures
import com.team376.pulsemetry.dashboard.support.SourceFixtures.Event
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutoSnapshotTest {
	@BeforeAll
	fun schemas() = DashboardTestStores.ensureSchemas()

	@Test
	@DisplayName("자동 snapshot은 저장된 asOf 직전만 복사하고 일별 계약은 기존 범위를 유지한다")
	fun strictCutoffAlsoAppliesToObservationMetadata() {
		val asOf = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusNanos(123456000)
		val clock = MutableClock(asOf.plusNanos(789))
		val assembly = SnapshotAssembly(clock = clock)
		val tenant = UUID.randomUUID()
		val member = UUID.randomUUID()
		val today = asOf.atZone(QueryReader.SEOUL).toLocalDate()
		val period = ComparedPeriod(DatePeriod(today, today.plusDays(1), QueryReader.SEOUL), CompareMode.NONE)
		SourceFixtures.insertEvents(tenant,
			Event("before", asOf.minusNanos(1), memberId = member),
			Event("at", asOf, memberId = member),
			Event("after", asOf.plusNanos(1), memberId = member),
		)
		SourceFixtures.insertMetricPoint(tenant, "future-metric", period.current.until.minusSeconds(1))
		SourceFixtures.insertEvents(tenant, Event("future-generic", period.current.until.minusSeconds(1), usage = false))

		val auto = assembly.service.create(tenant, member, period, TrendBucketMode.AUTO)
		assertThat(auto.asOf).isEqualTo(asOf)
		assertThat(auto.queryContract).isEqualTo(SnapshotManifestStore.AUTO_QUERY_CONTRACT)
		assertThat(auto.usageRows).isEqualTo(1)
		assertThat(auto.observedDayRows).isEqualTo(1)
		assertThat(DashboardTestStores.clickHouseAdmin(
			"SELECT sum(observations) FROM dashboard_cache.snapshot_observed_days WHERE build_id = '${auto.buildId}'",
		).trim()).isEqualTo("1")
		assertThat(DashboardTestStores.clickHouseAdmin(
			"SELECT observation_id FROM dashboard_cache.snapshot_usage WHERE build_id = '${auto.buildId}'",
		).trim()).isEqualTo(SourceFixtures.observationId("before"))

		val day = assembly.service.create(tenant, member, period)
		assertThat(day.queryContract).isEqualTo(SnapshotManifestStore.QUERY_CONTRACT)
		assertThat(day.usageRows).isEqualTo(3)
		assertThat(day.observedDayRows).isEqualTo(2)
	}

	@Test
	@DisplayName("미래 선택 기간의 비교 범위에도 같은 발생 시각 상한을 적용한다")
	fun comparisonUsesTheSameCutoff() {
		val asOf = Instant.now().truncatedTo(ChronoUnit.MICROS)
		val today = asOf.atZone(QueryReader.SEOUL).toLocalDate()
		val period = ComparedPeriod(DatePeriod(today.plusDays(7), today.plusDays(7), QueryReader.SEOUL), CompareMode.PREV_WEEK)
		val tenant = UUID.randomUUID()
		SourceFixtures.insertEvents(tenant, Event("previous-before", asOf.minusNanos(1)), Event("previous-at", asOf),
			Event("current-future", period.current.from.plusSeconds(1)))
		SourceFixtures.insertMetricPoint(tenant, "previous-future-metric", asOf.plusNanos(1))
		val ready = SnapshotAssembly(clock = MutableClock(asOf)).service.create(tenant, UUID.randomUUID(), period, TrendBucketMode.AUTO)
		assertThat(ready.usageRows).isEqualTo(1)
		assertThat(DashboardTestStores.clickHouseAdmin(
			"SELECT in_current, in_previous FROM dashboard_cache.snapshot_usage WHERE build_id = '${ready.buildId}' FORMAT TSVRaw",
		).trim()).isEqualTo("false\ttrue")
		assertThat(DashboardTestStores.clickHouseAdmin(
			"SELECT sum(observations) FROM dashboard_cache.snapshot_observed_days WHERE build_id = '${ready.buildId}'",
		).trim()).isEqualTo("1")
	}

	@Test
	@DisplayName("집계 모드 변경은 409이고 users의 모드 계승과 고정 정책 메타는 만료 전까지만 유효하다")
	fun contractInheritanceAndExpiry() {
		val clock = MutableClock(Instant.now().truncatedTo(ChronoUnit.MICROS))
		val assembly = SnapshotAssembly(clock = clock)
		val today = clock.instant().atZone(QueryReader.SEOUL).toLocalDate()
		val period = ComparedPeriod(DatePeriod(today, today, QueryReader.SEOUL), CompareMode.NONE)
		val tenant = UUID.randomUUID()
		val member = UUID.randomUUID()
		val auto = assembly.service.create(tenant, member, period, TrendBucketMode.AUTO)
		val day = assembly.service.create(tenant, member, period)
		fun expired(block: () -> Unit) = assertThatThrownBy(block).isInstanceOfSatisfying(DashboardException::class.java) {
			assertThat(it.code).isEqualTo(ErrorCode.SNAPSHOT_EXPIRED)
		}
		expired { assembly.service.obtain(tenant, member, period, true, auto.snapshotId, TrendBucketMode.DAY) }
		expired { assembly.service.obtain(tenant, member, period, true, day.snapshotId, TrendBucketMode.AUTO) }
		val inherited = assembly.service.obtain(tenant, member, period, false, auto.snapshotId, bucket = null)
		assertThat(inherited).isEqualTo(auto)
		val policy = CollectionPolicyAnnotations.pending(inherited)
		clock.now = clock.now.plusSeconds(30)
		assertThat(CollectionPolicyAnnotations.pending(assembly.service.requireReady(auto.snapshotId, tenant))).isEqualTo(policy)
		assertThat(policy.asOf).isEqualTo(auto.asOf.toString())
		assertThat(policy.status).isEqualTo("pending")
		assertThat(policy.annotations.single().reasons).containsExactly("policy_integration_pending")
		assertThat(policy.annotations.single().evidenceRefs).isEmpty()
		clock.now = requireNotNull(auto.expiresAt)
		expired { assembly.service.obtain(tenant, member, period, false, auto.snapshotId, bucket = null) }
	}
}
