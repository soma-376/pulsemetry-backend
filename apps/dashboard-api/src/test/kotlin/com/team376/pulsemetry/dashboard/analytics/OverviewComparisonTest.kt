package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.organization.Organization
import com.team376.pulsemetry.dashboard.request.CompareMode
import com.team376.pulsemetry.dashboard.request.ComparedPeriod
import com.team376.pulsemetry.dashboard.request.DatePeriod
import com.team376.pulsemetry.dashboard.request.QueryReader
import com.team376.pulsemetry.dashboard.source.ClickHouseSourceReader
import com.team376.pulsemetry.dashboard.store.ClickHouseConnection
import com.team376.pulsemetry.dashboard.support.DashboardTestStores
import com.team376.pulsemetry.dashboard.support.SnapshotAssembly
import com.team376.pulsemetry.dashboard.support.SourceFixtures
import com.team376.pulsemetry.dashboard.support.SourceFixtures.Event
import com.team376.pulsemetry.persistence.telemetryops.TenantIngestSummaryStore
import com.team376.pulsemetry.persistence.telemetryops.TenantSummaryBackfill
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.datasource.DriverManagerDataSource
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * 비교를 공개하는 계산 경로 (사례 10). v1 의 정책은 완전 관측이 없어 비교를 공개하지 않으므로, **공개 조건을 만족한다고 가정한 정책**을
 * 끼워 경로 자체를 본다 — 이전 기간은 전체 권한 범위에서 집계하고, 상위 3팀의 이전 값은 같은 팀 ID 로 고른다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OverviewComparisonTest {

	@BeforeAll
	fun schemas() {
		DashboardTestStores.ensureSchemas()
		SourceFixtures.completeBackfill()
	}

	private fun kst(dateTime: String) = LocalDateTime.parse(dateTime).atZone(QueryReader.SEOUL).toInstant()

	private fun service(policy: ComparisonPolicy): OverviewService {
		val assembly = SnapshotAssembly()
		val postgres = DashboardTestStores.postgres
		val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
		val mapper = JsonMapper.builder().build()
		val ledger = ClickHouseSourceReader(
			ClickHouseConnection(DashboardTestStores.clickHouseUrl(), "default", "default", "", Duration.ofSeconds(10), mapper),
			Duration.ofSeconds(10), 100_000, 16_000_000,
		)
		return OverviewService(
			snapshots = assembly.service,
			aggregator = UsageAggregator(assembly.clickHouse),
			references = SnapshotReferences(assembly.clickHouse, DashboardTestStores.writer),
			ingest = IngestStatusReader(TenantIngestSummaryStore(dataSource), TenantSummaryBackfill(dataSource), ledger, DashboardTestStores.writer),
			comparison = policy,
			clock = Clock.systemUTC(),
		)
	}

	@Test
	@DisplayName("사례 10 — 현재 상위 A·B·C, 이전에는 D 만 쓴 경우 이전 값은 같은 ID 로 고르고 D 는 나머지 팀의 이전 금액에 들어간다")
	fun previousValuesFollowTheSameTeamIds() {
		val tenant = DashboardTestStores.insertTenant()
		SourceFixtures.setSummary(tenant, firstReceivedAt = kst("2026-08-01T00:00:00"))
		val teams = listOf("A", "B", "C", "D", "E").associateWith { SourceFixtures.insertTeam(tenant, it) }
		fun usage(name: String, team: String, day: String, cost: String) = Event(
			"$tenant-$name", kst("${day}T10:00:00"), teamId = teams.getValue(team), memberId = UUID.randomUUID(),
			costEstimatedUsd = BigDecimal(cost), pricingVersion = "v1",
		)
		SourceFixtures.insertEvents(
			tenant,
			usage("a", "A", "2026-09-08", "30"), usage("b", "B", "2026-09-08", "20"), usage("c", "C", "2026-09-08", "10"),
			usage("e", "E", "2026-09-09", "5"),
			usage("a-prev", "A", "2026-09-01", "7"), usage("d-prev", "D", "2026-09-02", "50"),
		)
		val period = ComparedPeriod(DatePeriod(LocalDate.parse("2026-09-07"), LocalDate.parse("2026-09-13"), QueryReader.SEOUL), CompareMode.PREV_WEEK)

		val response = service { _, _ -> true }.overview(Organization(tenant, "조직", "Asia/Seoul"), UUID.randomUUID(), period)

		assertThat(response.comparison.status).isEqualTo("available")
		assertThat(response.usage.previous!!.equivalentCostUsd).isEqualTo("57.000000")
		val top = response.teamUsage.topTeams
		assertThat(top.map { it.teamName }).containsExactly("A", "B", "C")
		assertThat(top.map { it.previous!!.equivalentCostUsd }).containsExactly("7.000000", null, null)
		assertThat(response.teamUsage.otherTeams.previousEquivalentCostUsd).isEqualTo("50.000000")
		assertThat(response.teamUsage.otherTeams.currentEquivalentCostUsd).isEqualTo("5.000000")

		// v1 의 정책으로는 같은 입력에서도 비교를 공개하지 않는다 — previous 전부 null.
		val v1 = service(ComparisonPolicy.COMPLETE_ONLY).overview(Organization(tenant, "조직", "Asia/Seoul"), UUID.randomUUID(), period)
		assertThat(v1.comparison.status).isEqualTo("unavailable")
		assertThat(v1.usage.previous).isNull()
		assertThat(v1.teamUsage.topTeams.map { it.previous }).containsOnlyNulls()
		assertThat(v1.teamUsage.otherTeams.previousEquivalentCostUsd).isNull()
	}
}
