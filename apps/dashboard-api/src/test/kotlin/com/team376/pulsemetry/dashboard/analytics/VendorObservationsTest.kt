package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.request.QueryReader
import com.team376.pulsemetry.dashboard.snapshot.RetentionBoundaryReader
import com.team376.pulsemetry.dashboard.snapshot.SnapshotCompleteness
import com.team376.pulsemetry.dashboard.source.ClickHouseSourceReader
import com.team376.pulsemetry.dashboard.store.ClickHouseConnection
import com.team376.pulsemetry.dashboard.support.DashboardTestStores
import com.team376.pulsemetry.dashboard.support.SourceFixtures
import com.team376.pulsemetry.dashboard.support.SourceFixtures.Event
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

/**
 * 벤더 관측 지표 (ADR 0044). 기대값은 ADR 의 규칙에서 쓴다 — 관측 제품은 카탈로그의 명시 매핑(`claude_code` → `claude_team`, `codex` → `openai_biz`)으로만
 * 잇고, 매핑 없는 관측은 따로 센다. 최근 7·30일은 기준일 전날까지다. 조회 시간대는 서울, 확정 대기 1시간.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VendorObservationsTest {

	@BeforeAll
	fun schemas() {
		DashboardTestStores.ensureSchemas()
	}

	private val reader = ClickHouseSourceReader(
		ClickHouseConnection(DashboardTestStores.clickHouseUrl(), "default", "default", "", Duration.ofSeconds(10), JsonMapper.builder().build()),
		Duration.ofSeconds(10), 100_000, 16_000_000,
	)
	private val observations by lazy {
		val postgres = DashboardTestStores.postgres
		val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
		VendorObservations(reader, DashboardTestStores.writer, DashboardTestStores.writer, TransactionTemplate(DataSourceTransactionManager(dataSource)),
			SnapshotCompleteness(DashboardTestStores.writer, DashboardTestStores.writer, Duration.ofHours(1)), RetentionBoundaryReader(DashboardTestStores.writer))
	}

	private fun kst(dateTime: String): Instant = LocalDateTime.parse(dateTime).atZone(QueryReader.SEOUL).toInstant()
	private val asOf = kst("2026-09-21T10:00:00")
	private fun fix(tenant: UUID, at: Instant = asOf) = observations.fix(tenant, at, QueryReader.SEOUL)
	private fun claude(tenant: UUID, time: String, member: UUID, recordStatus: String = "active") =
		Event("$tenant-${UUID.randomUUID()}", kst(time), memberId = member, recordStatus = recordStatus)
	private fun other(tenant: UUID, product: String, time: String, member: UUID) =
		Event("$tenant-${UUID.randomUUID()}", kst(time), product = product, serviceName = product, memberId = member, semanticsProfile = null)

	@Test
	@DisplayName("매핑된 관측은 그 카탈로그 제품에, 매핑 없는 관측은 따로 센다 — 7·30일은 기준일 전날까지이고 기준 시각 뒤와 제외된 행은 세지 않는다")
	fun mappedWindowsAndUnmapped() {
		val tenant = UUID.randomUUID()
		val (alice, bob, carol, dave, erin, frank, gina, hank, ivan) = List(9) { UUID.randomUUID() }
		SourceFixtures.insertEvents(
			tenant,
			claude(tenant, "2026-09-14T00:00:00", alice), // 7일 창의 첫 순간
			claude(tenant, "2026-09-13T23:59:59", bob), // 7일 창 밖, 30일 창 안
			claude(tenant, "2026-08-22T00:00:00", carol), // 30일 창의 첫 순간
			claude(tenant, "2026-08-21T23:59:59", dave), // 30일 창 밖 — 처음 관측
			claude(tenant, "2026-09-21T09:00:00", erin), // 오늘(기준 시각 전) — 마지막 관측이지만 창에는 없다
			claude(tenant, "2026-09-21T11:00:00", frank), // 기준 시각 뒤
			claude(tenant, "2026-09-20T12:00:00", ivan, recordStatus = "excluded"),
			other(tenant, "codex", "2026-09-20T12:00:00", gina),
			other(tenant, "unknown", "2026-09-19T12:00:00", hank),
		)

		val fixed = fix(tenant)

		assertThat(fixed.mappedProducts).containsExactly("claude_team", "openai_biz")
		assertThat(fixed.observed.keys).containsExactlyInAnyOrder("claude_team", "openai_biz", VendorObservations.UNMAPPED)
		val claude = fixed.observed.getValue("claude_team")
		assertThat(listOf(claude.firstSeenAt, claude.lastSeenAt)).containsExactly(kst("2026-08-21T23:59:59"), kst("2026-09-21T09:00:00"))
		assertThat(listOf(claude.users7d, claude.users30d)).containsExactly(1L, 3L)
		assertThat(claude.observedProducts).containsExactly("claude_code")
		val codex = fixed.observed.getValue("openai_biz")
		assertThat(listOf(codex.users7d, codex.users30d, codex.observedProducts)).containsExactly(1L, 1L, listOf("codex"))
		// 매핑 없는 관측은 어떤 카탈로그 제품에도 들어가지 않는다.
		val unmapped = fixed.observed.getValue(VendorObservations.UNMAPPED)
		assertThat(listOf(unmapped.observedProducts, unmapped.users30d)).containsExactly(listOf("unknown"), 1L)
		// 수집 구간 근거가 없어 창은 완전하지 않다.
		assertThat(listOf(fixed.complete7d, fixed.complete30d)).containsExactly(false, false)
	}

	@Test
	@DisplayName("한 기준 시각은 한 번 고정한다 — 뒤에 들어온 관측은 그 기준 시각의 값을 바꾸지 않고 새 기준 시각에서 보인다")
	fun fixedOncePerAsOf() {
		val tenant = UUID.randomUUID()
		SourceFixtures.insertEvents(tenant, claude(tenant, "2026-09-18T10:00:00", UUID.randomUUID()))
		val first = fix(tenant)
		SourceFixtures.insertEvents(tenant, claude(tenant, "2026-09-19T10:00:00", UUID.randomUUID()))

		assertThat(fix(tenant)).isEqualTo(first)
		assertThat(observations.read(tenant, asOf)).isEqualTo(first)
		assertThat(first.observed.getValue("claude_team").users7d).isEqualTo(1)
		assertThat(fix(tenant, asOf.plusMillis(1)).observed.getValue("claude_team").users7d).isEqualTo(2)
		// 고정하지 않은 기준 시각은 없다.
		assertThat(observations.read(tenant, asOf.minusMillis(1))).isNull()
	}

	@Test
	@DisplayName("창의 모든 날이 완전 관측이면 구성원 수는 정확하다(0 포함) — 매핑 없는 카탈로그 제품은 관측할 수 없다")
	fun completeWindow() {
		val tenant = DashboardTestStores.insertTenant()
		val admin = SourceFixtures.insertMember(tenant, "admin-${UUID.randomUUID()}@example.test", role = "admin")
		SourceFixtures.insertManifest(tenant, 1, admin, signals = """{"logs":true,"metrics":true}""", activatedAt = kst("2026-07-01T00:00:00"))
		val installation = SourceFixtures.insertInstallation(tenant, admin)
		SourceFixtures.setInstallationTimes(installation, kst("2026-07-01T00:00:00"))
		SourceFixtures.insertSegment(installation, kst("2026-07-01T00:00:00"), asOf)
		SourceFixtures.insertEvents(tenant, other(tenant, "codex", "2026-09-10T12:00:00", admin))

		val fixed = fix(tenant)

		assertThat(listOf(fixed.complete7d, fixed.complete30d)).containsExactly(true, true)
		// claude_team: 매핑은 있고 관측이 없다 — 완전한 창의 0 은 실제 0 이다.
		assertThat(fixed.present("claude_team")).isEqualTo(VendorObservations.Presented(null, null, 0, 0, VendorObservations.COMPLETE))
		// openai_biz: 7일 창(9/14~)에는 없고 30일 창에 한 명.
		assertThat(fixed.present("openai_biz")).isEqualTo(VendorObservations.Presented(kst("2026-09-10T12:00:00"), kst("2026-09-10T12:00:00"), 0, 1, VendorObservations.COMPLETE))
		assertThat(fixed.present("copilot")).isEqualTo(VendorObservations.Presented(null, null, null, null, VendorObservations.UNOBSERVED))
	}

	@Test
	@DisplayName("완전하지 않은 창의 0 은 모르는 값이다 — 센 수가 있으면 그 수(부분 값), 관측이 없으면 unobserved")
	fun partialWindow() {
		val row = VendorObservations.Observed("claude_team", listOf("claude_code"), kst("2026-09-01T00:00:00"), kst("2026-09-10T00:00:00"), 0, 4)
		val fixed = VendorObservations.Fixed(asOf, setOf("claude_team", "openai_biz"), complete7d = false, complete30d = false, observed = mapOf("claude_team" to row))

		assertThat(fixed.present("claude_team")).isEqualTo(VendorObservations.Presented(row.firstSeenAt, row.lastSeenAt, null, 4, VendorObservations.PARTIAL))
		assertThat(fixed.present("openai_biz")).isEqualTo(VendorObservations.Presented(null, null, null, null, VendorObservations.UNOBSERVED))
		// 7일 창만 완전하면 7일 수는 정확하다.
		assertThat(fixed.copy(complete7d = true).present("claude_team").users7d).isEqualTo(0)
	}

	@Test
	@DisplayName("정리는 기한 전에 만든 고정만 지운다")
	fun purge() {
		val tenant = UUID.randomUUID()
		fix(tenant)
		assertThat(observations.purge(Instant.now().minusSeconds(3600))).isZero()
		assertThat(observations.read(tenant, asOf)).isNotNull()
		assertThat(observations.purge(Instant.now().plusSeconds(1))).isGreaterThanOrEqualTo(1)
		assertThat(observations.read(tenant, asOf)).isNull()
	}
}

private operator fun <T> List<T>.component6(): T = this[5]
private operator fun <T> List<T>.component7(): T = this[6]
private operator fun <T> List<T>.component8(): T = this[7]
private operator fun <T> List<T>.component9(): T = this[8]
