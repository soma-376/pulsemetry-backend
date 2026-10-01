package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.authentication.DashboardPrincipal
import com.team376.pulsemetry.dashboard.authentication.Role
import com.team376.pulsemetry.dashboard.request.QueryReader
import com.team376.pulsemetry.dashboard.support.AbstractDashboardApiTest
import com.team376.pulsemetry.dashboard.support.DashboardHttp
import com.team376.pulsemetry.dashboard.support.DashboardTestStores
import com.team376.pulsemetry.dashboard.support.SourceFixtures
import com.team376.pulsemetry.dashboard.support.SourceFixtures.Event
import com.team376.pulsemetry.dashboard.support.TestDashboardAuthenticator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 설정의 종량 지출 = 벤더 청구 누계 (ADR 0050). 기대값은 ADR 의 가용성 표와 합계 규칙에서 쓴다 — 저장된 청구 누계만 원천이고 환산 비용·계약액과 독립이며,
 * 달력 달 기간은 이번 달 시작에서 시작해야 하고, 한 제품이라도 값이 없거나 기간이 다르면 조직 합계를 내지 않는다.
 */
class VendorBillingApiTest : AbstractDashboardApiTest() {

	@BeforeEach
	fun backfillDone() { SourceFixtures.completeBackfill() }

	private val now: Instant = Instant.now()
	private val monthStart: Instant = now.atZone(QueryReader.SEOUL).toLocalDate().withDayOfMonth(1).atStartOfDay(QueryReader.SEOUL).toInstant()

	private fun ok(tenant: UUID, path: String): JsonNode = http.send("/api/v1/organizations/$tenant$path",
		headers = mapOf("Authorization" to TestDashboardAuthenticator.header(DashboardPrincipal(tenant, UUID.randomUUID(), Role.ADMIN)))).let {
		assertThat(it.statusCode()).describedAs(it.body()).isEqualTo(200)
		DashboardHttp.json(it)
	}

	private fun organization(): Pair<UUID, UUID> {
		val tenant = DashboardTestStores.insertTenant()
		SourceFixtures.setSummary(tenant, firstReceivedAt = now.minus(Duration.ofDays(90)))
		val admin = SourceFixtures.insertMember(tenant, "admin-${UUID.randomUUID()}@example.test", role = "admin")
		SourceFixtures.insertManifest(tenant, 1, admin, signals = """{"logs":true,"metrics":true,"traces":true}""", activatedAt = now.minus(Duration.ofDays(90)))
		return tenant to admin
	}

	/** 등록 제품과 계약(좌석 [seats]석 × 30달러). */
	private fun register(tenant: UUID, admin: UUID, kind: String, plan: String, seats: Int = 5): String {
		val id = "$kind-${UUID.randomUUID()}"
		val at = Timestamp.from(now.minus(Duration.ofDays(90)))
		DashboardTestStores.writer.sql("INSERT INTO enrollment.managed_vendors (tenant_id,vendor_id,kind,source,created_at) VALUES (:t,:id,:kind,'manual',:at)")
			.param("t", tenant).param("id", id).param("kind", kind).param("at", at).update()
		val contract = """{"version":1,"planId":"$plan","effectiveFrom":"2026-01-01","effectiveTo":null,"termNote":null,
			"tiers":[{"tierId":"tier-$kind","label":"Standard","seats":$seats,"monthlyFeePerSeatUsd":"30"}],"monthlySeatFeeUsd":"${30 * seats}","confirmedAt":"2026-01-01T00:00:00Z","confirmedBy":"$admin"}"""
		DashboardTestStores.writer.sql("INSERT INTO enrollment.vendor_contract_versions (tenant_id,vendor_id,version,display_name,contract,recorded_at,recorded_by) VALUES (:t,:id,1,:name,CAST(:c AS jsonb),:at,:admin)")
			.param("t", tenant).param("id", id).param("name", "$kind 계약").param("c", contract).param("at", at).param("admin", admin).update()
		return id
	}

	private fun connect(tenant: UUID, admin: UUID, vendorId: String, connector: String, billedAt: Instant? = null, failedAt: Instant? = null): UUID {
		val id = UUID.randomUUID()
		DashboardTestStores.writer.sql("""INSERT INTO enrollment.vendor_connections (id, tenant_id, vendor_id, connector, settings, credential_ciphertext, credential_key_id,
				credential_updated_at, check_status, version, created_at, created_by, updated_at, updated_by, last_billing_succeeded_at, last_billing_failed_at, last_billing_error)
			VALUES (:id, :t, :v, :c, '{}', 'opaque', 'k1', :at, 'verified', 1, :at, :admin, :at, :admin, :billed, :failed, :error)""")
			.param("id", id).param("t", tenant).param("v", vendorId).param("c", connector).param("at", Timestamp.from(now.minus(Duration.ofDays(30)))).param("admin", admin)
			.param("billed", billedAt?.let(Timestamp::from), java.sql.Types.TIMESTAMP).param("failed", failedAt?.let(Timestamp::from), java.sql.Types.TIMESTAMP)
			.param("error", failedAt?.let { "vendor_unavailable" }, java.sql.Types.VARCHAR).update()
		return id
	}

	private fun billed(tenant: UUID, vendorId: String, connection: UUID, start: Instant, end: Instant, amount: String, kind: String, fetchedAt: Instant) {
		DashboardTestStores.writer.sql("""INSERT INTO enrollment.vendor_billing_periods (tenant_id, vendor_id, period_start, period_end, amount_usd, kind, finalized, source, connection_id, fetched_at)
			VALUES (:t, :v, :start, :end, :amount, :kind, false, 'connector', :c, :fetched)""")
			.param("t", tenant).param("v", vendorId).param("start", Timestamp.from(start)).param("end", Timestamp.from(end)).param("amount", BigDecimal(amount))
			.param("kind", kind).param("c", connection).param("fetched", Timestamp.from(fetchedAt)).update()
	}

	private fun metered(settings: JsonNode, vendorId: String): JsonNode =
		settings.at("/vendors/items").toList().single { it.path("vendorId").asString() == vendorId }.path("meteredMonthToDate")
	private fun section(node: JsonNode) = node.path("availability").asString() to node.path("reason").takeUnless { it.isNull }?.asString()

	@Test
	@DisplayName("벤더 청구 누계만 원천이다 — 환산 비용·계약액과 다른 값이 그대로 나오고, 청구를 구현하지 않은 제품·연결 없는 제품은 그 제품만 사유와 함께 없다")
	fun billedAmountIsIndependent() {
		val (tenant, admin) = organization()
		val claude = register(tenant, admin, "claude_team", "enterprise")
		val copilot = register(tenant, admin, "copilot", "copilot_business")
		val cursor = register(tenant, admin, "cursor", "cursor_enterprise")
		val openai = register(tenant, admin, "openai_biz", "business")
		val connection = connect(tenant, admin, claude, "claude_enterprise", billedAt = now.minus(Duration.ofHours(1)))
		connect(tenant, admin, copilot, "copilot")
		// 환산 비용(이번 달 claude_code 사용 7달러)·월 계약액(150달러)과 다른 청구 누계 412.8달러.
		val member = SourceFixtures.insertMember(tenant, "dev-${UUID.randomUUID()}@example.test")
		SourceFixtures.insertEvents(tenant, Event("$tenant-cost", maxOf(monthStart, now.minus(Duration.ofHours(2))), memberId = member, sessionId = "s",
			costEstimatedUsd = BigDecimal("7"), pricingVersion = "v1"))
		billed(tenant, claude, connection, monthStart, now.minus(Duration.ofHours(1)), "412.80000000", "usage_cost", now.minus(Duration.ofHours(1)))

		val settings = ok(tenant, "/settings")
		with(metered(settings, claude)) {
			assertThat(section(this)).isEqualTo("available" to null)
			assertThat(path("data").path("actualBilledUsd").asString().toBigDecimal()).isEqualByComparingTo("412.8")
			assertThat(path("data").path("equivalentCostUsd").isNull).describedAs("환산 비용을 청구액 자리에 두지 않는다").isTrue()
			assertThat(listOf(path("data").path("startDate").asString(), path("data").path("billingKind").asString(), path("data").path("finalized").asBoolean(), path("data").path("source").asString()))
				.containsExactly(monthStart.atZone(QueryReader.SEOUL).toLocalDate().toString(), "usage_cost", false, "connector")
		}
		assertThat(section(metered(settings, copilot))).describedAs("Copilot 은 청구 API 가 없다").isEqualTo("unavailable" to "billing_not_supported")
		assertThat(section(metered(settings, openai))).describedAs("커넥터 없는 플랜").isEqualTo("unavailable" to "billing_not_supported")
		assertThat(section(metered(settings, cursor))).describedAs("청구를 구현했지만 연결이 없다").isEqualTo("unavailable" to "billing_source_not_connected")
		// 한 제품이라도 없으면 합계를 부분합으로 내지 않는다.
		with(settings.at("/summary/meteredMonthToDate")) {
			assertThat(section(this)).isEqualTo("partial" to "billing_not_supported")
			assertThat(path("data").path("actualBilledUsd").isNull && path("data").path("equivalentCostUsd").isNull).isTrue()
		}
		assertThat(section(ok(tenant, "/vendors/$claude").at("/vendor/meteredMonthToDate"))).describedAs("상세도 같은 값").isEqualTo("available" to null)
	}

	@Test
	@DisplayName("달력 달 누계는 이번 달 시작에서 시작해야 지금 값이다 — 지난달 값은 없는 것이고, 실패·낡음은 값을 두고 낮춘다")
	fun periodAndFreshness() {
		val (tenant, admin) = organization()
		val claude = register(tenant, admin, "claude_team", "enterprise")
		val connection = connect(tenant, admin, claude, "claude_enterprise", billedAt = monthStart.minus(Duration.ofHours(2)))
		val lastMonth = monthStart.atZone(QueryReader.SEOUL).minusMonths(1).toInstant()
		billed(tenant, claude, connection, lastMonth, monthStart.minus(Duration.ofHours(2)), "99", "usage_cost", monthStart.minus(Duration.ofHours(2)))
		assertThat(section(metered(ok(tenant, "/settings"), claude))).isEqualTo("unavailable" to "billing_sync_pending")
		DashboardTestStores.writer.sql("UPDATE enrollment.vendor_connections SET last_billing_failed_at = :at, last_billing_error = 'vendor_unavailable' WHERE id = :id")
			.param("at", Timestamp.from(now.minus(Duration.ofMinutes(5)))).param("id", connection).update()
		assertThat(section(metered(ok(tenant, "/settings"), claude))).isEqualTo("unavailable" to "billing_sync_failing")

		// 이번 달 값이 있다 — 그 뒤 읽기가 실패하면 partial failing, 실패가 없어도 오래되면 partial outdated(기준 26시간).
		billed(tenant, claude, connection, monthStart, monthStart.plus(Duration.ofMinutes(30)), "12.5", "usage_cost", now.minus(Duration.ofHours(30)))
		with(metered(ok(tenant, "/settings"), claude)) {
			assertThat(section(this)).isEqualTo("partial" to "billing_sync_failing")
			assertThat(path("data").path("actualBilledUsd").asString().toBigDecimal()).isEqualByComparingTo("12.5")
		}
		DashboardTestStores.writer.sql("UPDATE enrollment.vendor_connections SET last_billing_succeeded_at = :at, last_billing_failed_at = NULL, last_billing_error = NULL WHERE id = :id")
			.param("at", Timestamp.from(now.minus(Duration.ofHours(30)))).param("id", connection).update()
		assertThat(section(metered(ok(tenant, "/settings"), claude))).isEqualTo("partial" to "billing_sync_outdated")
	}

	@Test
	@DisplayName("조직 합계 — 모든 제품의 값이 같은 기간일 때만 더하고, 청구 주기가 달력 달과 다르면 더하지 않는다")
	fun summary() {
		val (tenant, admin) = organization()
		val claude = register(tenant, admin, "claude_team", "enterprise")
		val cursor = register(tenant, admin, "cursor", "cursor_enterprise")
		val claudeConnection = connect(tenant, admin, claude, "claude_enterprise", billedAt = now.minus(Duration.ofMinutes(10)))
		val cursorConnection = connect(tenant, admin, cursor, "cursor_enterprise", billedAt = now.minus(Duration.ofMinutes(10)))
		val fetched = now.minus(Duration.ofMinutes(10))
		billed(tenant, claude, claudeConnection, monthStart, fetched, "100.25", "usage_cost", fetched)
		// Cursor 의 청구 주기는 벤더가 정한다 — 달력 달 시작보다 3일 앞에서 시작했다.
		val cycleStart = monthStart.minus(Duration.ofDays(3))
		billed(tenant, cursor, cursorConnection, cycleStart, fetched, "24.50125487", "usage_spend", fetched)
		with(ok(tenant, "/settings")) {
			assertThat(metered(this, cursor).path("data").path("billingKind").asString()).isEqualTo("usage_spend")
			assertThat(section(at("/summary/meteredMonthToDate"))).isEqualTo("partial" to "billing_periods_differ")
			assertThat(at("/summary/meteredMonthToDate/data/actualBilledUsd").isNull).isTrue()
		}
		// 주기가 달력 달과 같은 기간이면 더한다.
		DashboardTestStores.writer.sql("UPDATE enrollment.vendor_billing_periods SET period_start = :start WHERE vendor_id = :v")
			.param("start", Timestamp.from(monthStart)).param("v", cursor).update()
		with(ok(tenant, "/settings").at("/summary/meteredMonthToDate")) {
			assertThat(section(this)).isEqualTo("available" to null)
			assertThat(path("data").path("actualBilledUsd").asString().toBigDecimal()).isEqualByComparingTo("124.75125487")
		}
		// 등록 제품이 없으면 해당 없다.
		val (empty, _) = organization()
		assertThat(section(ok(empty, "/settings").at("/summary/meteredMonthToDate"))).isEqualTo("unavailable" to "not_applicable")
	}
}
