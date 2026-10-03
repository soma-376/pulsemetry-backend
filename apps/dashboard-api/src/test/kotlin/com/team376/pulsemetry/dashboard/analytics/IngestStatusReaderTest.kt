package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.source.ClickHouseSourceReader
import com.team376.pulsemetry.dashboard.store.ClickHouseConnection
import com.team376.pulsemetry.dashboard.support.DashboardTestStores
import com.team376.pulsemetry.dashboard.support.SourceFixtures
import com.team376.pulsemetry.persistence.telemetryops.TenantIngestSummaryStore
import com.team376.pulsemetry.persistence.telemetryops.TenantSummaryBackfill
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 판정의 근거를 읽는 쪽 (ADR 0041). 읽지 못한 것과 없는 것을 가른다 — 읽지 못하면 빈 값이 아니라 null 이고, 그 결과는 `unknown` 이다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IngestStatusReaderTest {

	@BeforeAll
	fun schemas() {
		DashboardTestStores.ensureSchemas()
	}

	private val mapper = JsonMapper.builder().build()
	private val postgres = DashboardTestStores.postgres
	private val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)

	private fun ledger(url: String) = ClickHouseSourceReader(
		ClickHouseConnection(url, "default", "default", "", Duration.ofSeconds(2), mapper), Duration.ofSeconds(2), 100_000, 16_000_000,
	)

	private fun reader(source: JdbcClient = DashboardTestStores.writer, clickHouseUrl: String = DashboardTestStores.clickHouseUrl()) =
		IngestStatusReader(TenantIngestSummaryStore(dataSource), TenantSummaryBackfill(dataSource), ledger(clickHouseUrl), source)

	/** 아무도 듣지 않는 포트. 연결이 거부된다. */
	private val unreachable = "127.0.0.1:1"

	@Test
	@DisplayName("설치 보고를 읽으면 활성 설치마다 마지막 보고와 창 안의 손실 여부가 나온다 — 폐기된 설치와 다른 조직은 빠진다")
	fun readsReportsOfActiveInstallations() {
		val tenant = DashboardTestStores.insertTenant()
		val other = DashboardTestStores.insertTenant()
		val member = SourceFixtures.insertMember(tenant, "reader-${UUID.randomUUID()}@example.test")
		val suspended = SourceFixtures.insertMember(tenant, "suspended-${UUID.randomUUID()}@example.test", status = "suspended")
		val since = Instant.parse("2026-09-30T02:45:00Z")
		val reported = Instant.parse("2026-09-30T02:59:00Z")

		val lossy = SourceFixtures.insertInstallation(tenant, member)
		SourceFixtures.setHeartbeat(lossy, reported, lastDeliveredAt = reported.minusSeconds(900), pendingSince = reported.minusSeconds(420))
		SourceFixtures.insertSegment(lossy, reported.minusSeconds(7200), reported.minusSeconds(1200), lost = 3) // 창 밖에서 끝났다.
		SourceFixtures.insertSegment(lossy, reported.minusSeconds(1200), since, lost = 1) // 창의 시작 시각에 끝났다 — 창 안이다.
		val direct = SourceFixtures.insertInstallation(tenant, suspended)
		SourceFixtures.setHeartbeat(direct, reported, mode = "direct", forwarding = false, receivingSince = null, lastDeliveredAt = null)
		SourceFixtures.insertSegment(direct, reported.minusSeconds(7200), since.minusNanos(1_000), lost = 5) // 창 밖.
		val unreported = SourceFixtures.insertInstallation(tenant, member)
		val revoked = SourceFixtures.insertInstallation(tenant, member, status = "revoked")
		SourceFixtures.setHeartbeat(revoked, reported)
		SourceFixtures.setHeartbeat(SourceFixtures.insertInstallation(other, SourceFixtures.insertMember(other, "other-${UUID.randomUUID()}@example.test")), reported)

		val states = reader().installations(tenant, since)!!.associateBy { it.installationId }
		assertThat(states.keys).containsExactlyInAnyOrder(lossy, direct, unreported)

		assertThat(states.getValue(lossy)).isEqualTo(InstallationState(lossy, member, true, InstallationState.Report(
			receivedAt = reported, local = true, forwarding = true, receivingSince = reported.minusSeconds(3600),
			lastDeliveredAt = reported.minusSeconds(900), pendingSince = reported.minusSeconds(420), recentLoss = true,
		)))
		assertThat(states.getValue(direct)).isEqualTo(InstallationState(direct, suspended, false, InstallationState.Report(
			receivedAt = reported, local = false, forwarding = false, receivingSince = null, lastDeliveredAt = null, pendingSince = null, recentLoss = false,
		)))
		assertThat(states.getValue(unreported)).isEqualTo(InstallationState(unreported, member, true, null))
	}

	@Test
	@DisplayName("설치 보고를 읽지 못하면 빈 목록이 아니라 null 이고, 판정은 확인 불가다")
	fun unreadableReportsAreNull() {
		val broken = JdbcClient.create(DriverManagerDataSource("jdbc:postgresql://$unreachable/none?connectTimeout=2", "nobody", "nothing"))
		val installations = reader(source = broken).installations(UUID.randomUUID(), Instant.now())
		assertThat(installations).isNull()

		val judgement = IngestJudgement.judge(true, installations, emptySet(), Instant.now(), IngestThresholds(Duration.ofMinutes(15), Duration.ofMinutes(5), Duration.ofHours(3)))
		assertThat(judgement.status to judgement.reason).isEqualTo("unknown" to "source_not_available")
		assertThat(listOf(judgement.activeInstallations, judgement.coverageTargetMembers, judgement.coverageRatio)).containsOnlyNulls()
	}

	@Test
	@DisplayName("수신 설치를 읽지 못하면 빈 집합이 아니라 null 이고, 관측 구성원 수도 0 이 아니라 null 이다")
	fun unreadableReceiptsAreNull() {
		val reader = reader(clickHouseUrl = "http://$unreachable")
		val observed = reader.observedInstallations(UUID.randomUUID(), Instant.now())
		assertThat(observed).isNull()
		assertThat(reader.observedMembers(UUID.randomUUID(), observed)).isNull()
		// 수신이 없다는 것은 읽은 결과다. 그때는 0 이다.
		assertThat(reader().observedMembers(UUID.randomUUID(), emptySet())).isEqualTo(0L)
	}
}
