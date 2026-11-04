package com.team376.pulsemetry.dashboard.alert

import com.team376.pulsemetry.dashboard.analytics.AnalyticsFrames
import com.team376.pulsemetry.dashboard.analytics.UsageAggregator
import com.team376.pulsemetry.dashboard.authentication.DashboardPrincipal
import com.team376.pulsemetry.dashboard.authentication.Role
import com.team376.pulsemetry.dashboard.snapshot.RetentionBoundaryReader
import com.team376.pulsemetry.dashboard.source.ClickHouseSourceReader
import com.team376.pulsemetry.dashboard.support.AbstractDashboardApiTest
import com.team376.pulsemetry.dashboard.support.DashboardHttp
import com.team376.pulsemetry.dashboard.support.DashboardTestStores
import com.team376.pulsemetry.dashboard.support.MutableClock
import com.team376.pulsemetry.dashboard.support.SourceFixtures
import com.team376.pulsemetry.dashboard.support.TestDashboardAuthenticator
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** HTTP 응답·원천 PostgreSQL·cache PostgreSQL을 함께 통과하는 공백 사건 계약(ADR 0076). */
class InstallationReportGapApiTest : AbstractDashboardApiTest() {
	@Autowired private lateinit var source: JdbcClient
	@Autowired private lateinit var reader: ClickHouseSourceReader
	@Autowired private lateinit var store: AlertStore
	@Autowired private lateinit var frames: AnalyticsFrames
	@Autowired private lateinit var aggregator: UsageAggregator
	@Autowired private lateinit var boundaries: RetentionBoundaryReader
	@Autowired private lateinit var service: AlertService

	private val basis = Instant.parse("2026-10-01T00:00:00Z")
	private val window = Duration.ofHours(168)
	private data class Fixture(val tenant: UUID, val member: UUID, val installation: UUID)

	private fun fixture(createdAt: Instant = basis, status: String = "active"): Fixture {
		val tenant = DashboardTestStores.insertTenant()
		val member = SourceFixtures.insertMember(tenant, "gap-${UUID.randomUUID()}@example.test", role = "owner")
		val installation = SourceFixtures.insertInstallation(tenant, member, status = status)
		SourceFixtures.setInstallationTimes(installation, createdAt, createdAt.takeIf { status == "revoked" })
		return Fixture(tenant, member, installation)
	}

	private fun evaluator(clock: MutableClock) = AlertEvaluator(source, reader, store, frames, aggregator, organizations, boundaries,
		Duration.ofHours(1), Duration.ofMinutes(10), clock)

	private fun get(f: Fixture, path: String) = http.send("/api/v1/organizations/${f.tenant}$path", headers = mapOf(
		"Authorization" to TestDashboardAuthenticator.header(DashboardPrincipal(f.tenant, f.member, Role.ADMIN))))

	private fun json(f: Fixture, path: String) = get(f, path).also { assertThat(it.statusCode()).describedAs(it.body()).isEqualTo(200) }.let(DashboardHttp::json)

	@Test
	@DisplayName("최초 미보고는 등록부터 정확히 168시간에 발생하며 폐기·최근 보고·AI 미사용은 공백이 아니다")
	fun thresholdAndTargets() {
		val f = fixture()
		val clock = MutableClock(basis.plus(window).minusSeconds(1))
		assertThat(json(f, "/alerts?category=collection").at("/evaluation/reasons").toList().map { it.asString() }).containsExactly("evaluation_pending")
		evaluator(clock).evaluate(f.tenant)
		assertThat(store.alerts(f.tenant)).isEmpty()
		clock.advance(Duration.ofSeconds(1))
		// 사용자 규칙이 하나도 없어도 주기 대상에 포함한다.
		evaluator(clock).runOnce()
		val alert = store.alerts(f.tenant).single()
		assertThat(alert.ruleId to alert.category).isEqualTo("installation_report_gap" to "collection")
		assertThat(alert.occurredAt).isEqualTo(clock.instant())
		val item = json(f, "/alerts?category=collection").at("/alerts/items/0")
		assertThat(item.path("eventCount").isNull).isTrue()
		assertThat(item.at("/evidence/kind").asString()).isEqualTo("installation_report_gap")
		assertThat(item.at("/evidence/basisAt").asString()).isEqualTo(basis.toString())
		assertThat(item.at("/evidence/lastReceivedAt").isNull).isTrue()
		assertThat(item.at("/evidence/resolutionDetectedAt").isNull).isTrue()
		val counts = service.overview(f.tenant, clock.instant())
		assertThat(listOf(counts.unacknowledgedTotal, counts.collection, counts.security, counts.cost)).containsExactly(1L, 1L, 0L, 0L)
		assertThat(json(f, "/alerts?category=security").at("/alerts/totalCount").asInt()).isZero()

		val retired = fixture(status = "revoked")
		evaluator(clock).evaluate(retired.tenant)
		assertThat(store.alerts(retired.tenant)).isEmpty()
		val idle = fixture()
		SourceFixtures.setHeartbeat(idle.installation, clock.instant(), receivingSince = null, lastDeliveredAt = null)
		evaluator(clock).evaluate(idle.tenant)
		assertThat(store.alerts(idle.tenant)).isEmpty()
		assertThat(get(idle, "/alerts/${alert.alertId}").statusCode()).isEqualTo(404)
	}

	@Test
	@DisplayName("확인·해소는 독립이며 반복 평가의 판은 고정되고 새 공백은 새 미확인 사건이다")
	fun acknowledgementResolutionAndRecurrence() {
		val f = fixture()
		val clock = MutableClock(basis.plus(window))
		val evaluator = evaluator(clock)
		evaluator.evaluate(f.tenant)
		val first = store.alerts(f.tenant).single()
		DashboardTestStores.writer.sql("""INSERT INTO enrollment.alert_acknowledgements
			(tenant_id, alert_id, alert_version, acknowledged_by, acknowledged_at) VALUES (:tenant,:id,1,:member,:at)""")
			.param("tenant", f.tenant).param("id", first.alertId).param("member", f.member).param("at", Timestamp.from(clock.instant())).update()
		clock.advance(Duration.ofMinutes(1))
		evaluator.evaluate(f.tenant)
		assertThat(store.alerts(f.tenant).single()).isEqualTo(first)
		assertThat(json(f, "/alerts/${first.alertId}").at("/alert/status").asString()).isEqualTo("open")
		assertThat(json(f, "/alerts?status=unacknowledged").at("/alerts/totalCount").asInt()).isZero()

		val resumedAt = clock.instant()
		SourceFixtures.setHeartbeat(f.installation, resumedAt)
		clock.advance(Duration.ofMinutes(1))
		evaluator.evaluate(f.tenant)
		val resolved = json(f, "/alerts/${first.alertId}").path("alert")
		assertThat(resolved.path("status").asString()).isEqualTo("closed")
		assertThat(resolved.path("version").asLong()).isEqualTo(first.version)
		assertThat(resolved.at("/evidence/lastReceivedAt").asString()).isEqualTo(resumedAt.toString())
		assertThat(resolved.at("/evidence/resolutionDetectedAt").asString()).isEqualTo(clock.instant().toString())
		assertThat(resolved.at("/evidence/resolutionReason").asString()).isEqualTo("report_resumed")
		assertThat(resolved.path("acknowledgement").isNull).isFalse()
		val resolvedStored = store.alerts(f.tenant).single()
		evaluator.evaluate(f.tenant)
		assertThat(store.alerts(f.tenant).single()).isEqualTo(resolvedStored)

		clock.now = resumedAt.plus(window)
		evaluator.evaluate(f.tenant)
		val second = store.alerts(f.tenant).single { it.status == "open" }
		assertThat(second.alertId).isNotEqualTo(first.alertId)
		assertThat(json(f, "/alerts/${second.alertId}").at("/alert/acknowledgement").isNull).isTrue()
		assertThat(service.overview(f.tenant, clock.instant()).collection).isEqualTo(1)
		DashboardTestStores.writer.sql("UPDATE enrollment.installations SET status = 'revoked' WHERE id = :id").param("id", f.installation).update()
		evaluator.runOnce()
		val retired = json(f, "/alerts/${second.alertId}").path("alert")
		assertThat(retired.at("/evidence/resolutionReason").asString()).isEqualTo("installation_revoked")
		assertThat(retired.path("acknowledgement").isNull).isTrue()
		assertThat(service.overview(f.tenant, clock.instant()).collection).describedAs("해소가 자동 확인을 만들지 않는다").isEqualTo(1)
	}

	@Test
	@DisplayName("중단 중 이미 끝난 공백은 복원하지 않고 원천 조회 뒤의 보고는 다음 회차에 반영한다")
	fun sourceSnapshotAndNoBackfill() {
		val f = fixture()
		val clock = MutableClock(basis.plus(Duration.ofDays(20)))
		SourceFixtures.setHeartbeat(f.installation, clock.instant())
		evaluator(clock).evaluate(f.tenant)
		assertThat(store.alerts(f.tenant)).isEmpty()
		clock.advance(window)
		val snapshot = InstallationReportGaps.read(source, f.tenant)
		SourceFixtures.setHeartbeat(f.installation, clock.instant())
		assertThat(store.claim(f.tenant, "snapshot", clock.instant(), clock.instant().plusSeconds(60))).isTrue()
		assertThat(store.withLease(f.tenant, "snapshot", clock) { InstallationReportGaps.reconcile(store, f.tenant, snapshot, clock.instant()) }).isTrue()
		store.release(f.tenant, "snapshot")
		assertThat(store.alerts(f.tenant).single().status).isEqualTo("open")
		evaluator(clock).evaluate(f.tenant)
		assertThat(store.alerts(f.tenant).single().status).isEqualTo("closed")
	}

	@Test
	@DisplayName("선점 만료·이전 소유자의 쓰기를 거부하고 종료·재발·평가 기록을 원자적으로 롤백한다")
	fun fencedAtomicReconciliation() {
		val f = fixture()
		val clock = MutableClock(basis.plus(window))
		evaluator(clock).evaluate(f.tenant)
		val original = store.alerts(f.tenant).single()
		val previous = store.evaluation(f.tenant, InstallationReportGaps.RULE)
		SourceFixtures.setHeartbeat(f.installation, clock.instant())
		clock.advance(window)
		val snapshot = InstallationReportGaps.read(source, f.tenant)
		assertThat(store.claim(f.tenant, "old", clock.instant(), clock.instant().plusSeconds(1))).isTrue()
		clock.advance(Duration.ofSeconds(2))
		var called = false
		assertThat(store.withLease(f.tenant, "old", clock) { called = true }).isFalse()
		assertThat(called).isFalse()
		assertThat(store.claim(f.tenant, "new", clock.instant(), clock.instant().plusSeconds(60))).isTrue()
		assertThat(store.withLease(f.tenant, "old", clock) { called = true }).isFalse()
		assertThat(called).isFalse()
		assertThatThrownBy {
			store.withLease(f.tenant, "new", clock) {
				InstallationReportGaps.reconcile(store, f.tenant, snapshot, clock.instant())
				throw IllegalStateException("원자성 검사")
			}
		}.isInstanceOf(IllegalStateException::class.java)
		assertThat(store.alerts(f.tenant)).containsExactly(original)
		assertThat(store.evaluation(f.tenant, InstallationReportGaps.RULE)).isEqualTo(previous)
		assertThat(store.withLease(f.tenant, "new", clock) {
			InstallationReportGaps.reconcile(store, f.tenant, snapshot, clock.instant())
			clock.advance(Duration.ofSeconds(60))
		}).isFalse()
		assertThat(store.alerts(f.tenant)).containsExactly(original)
		assertThat(store.evaluation(f.tenant, InstallationReportGaps.RULE)).isEqualTo(previous)
		store.release(f.tenant, "new")
		evaluator(clock).evaluate(f.tenant)
		assertThat(store.alerts(f.tenant).map { it.status }).containsExactlyInAnyOrder("open", "closed")
	}

	@Test
	@DisplayName("시스템 평가 실패는 확인된 알림 0건으로 투영하지 않는다")
	fun failedSystemEvaluationIsUnavailable() {
		val f = fixture()
		store.record(f.tenant, Evaluation(InstallationReportGaps.RULE, 1, null, null, basis, "failed", "evaluation_error", null, null))
		val summary = service.overview(f.tenant, basis)
		assertThat(summary.availability to summary.reason).isEqualTo("unavailable" to "evaluation_error")
		assertThat(summary.unacknowledgedTotal).isNull()
		assertThat(summary.collection).isNull()
		assertThat(json(f, "/alerts?category=collection").at("/evaluation/status").asString()).isEqualTo("unavailable")
	}

	@Test
	@DisplayName("두 평가기가 동시에 같은 조직을 평가해도 같은 공백 사건은 하나다")
	fun competingEvaluators() {
		val f = fixture()
		val clock = MutableClock(basis.plus(window))
		val start = CountDownLatch(1)
		val pool = Executors.newFixedThreadPool(2)
		try {
			val runs = (1..2).map { pool.submit<Boolean> { start.await(); evaluator(clock).evaluate(f.tenant) } }
			start.countDown()
			runs.forEach { it.get(30, TimeUnit.SECONDS) }
			assertThat(store.alerts(f.tenant)).hasSize(1)
			assertThat(store.alerts(f.tenant).single().version).isEqualTo(1)
		} finally { pool.shutdownNow() }
	}
}
