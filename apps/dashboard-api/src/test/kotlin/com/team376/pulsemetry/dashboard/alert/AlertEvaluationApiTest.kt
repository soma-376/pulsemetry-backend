package com.team376.pulsemetry.dashboard.alert

import com.team376.pulsemetry.dashboard.analytics.AnalyticsFrames
import com.team376.pulsemetry.dashboard.analytics.UsageAggregator
import com.team376.pulsemetry.dashboard.authentication.DashboardPrincipal
import com.team376.pulsemetry.dashboard.authentication.Role
import com.team376.pulsemetry.dashboard.request.QueryReader
import com.team376.pulsemetry.dashboard.snapshot.RetentionBoundaryReader
import com.team376.pulsemetry.dashboard.source.ClickHouseSourceReader
import com.team376.pulsemetry.dashboard.support.AbstractDashboardApiTest
import com.team376.pulsemetry.dashboard.support.DashboardHttp
import com.team376.pulsemetry.dashboard.support.DashboardTestStores
import com.team376.pulsemetry.dashboard.support.MutableClock
import com.team376.pulsemetry.dashboard.support.SourceFixtures
import com.team376.pulsemetry.dashboard.support.SourceFixtures.Event
import com.team376.pulsemetry.dashboard.support.TestDashboardAuthenticator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 알림 평가와 조회 (ADR 0051 §5·§6). 기대값은 ADR 의 규칙에서 쓴다 — 급증은 두 기간이 모두 완전할 때만 증가율 ≥ 임계값(0.4)이면 그날의 알림,
 * 24시간 규칙은 대상마다 24시간 안에 이어지는 위반을 한 묶음으로, 같은 사건은 다시 평가해도 하나다. 확인은 enrollment 의 기록이다.
 * 테스트 설정의 확정 대기는 1시간이다. 평가는 시각을 바꿔 끼운 평가기로 직접 부른다(주기 실행은 테스트에서 돌지 않는다).
 */
class AlertEvaluationApiTest : AbstractDashboardApiTest() {

	@Autowired private lateinit var source: JdbcClient
	@Autowired private lateinit var reader: ClickHouseSourceReader
	@Autowired private lateinit var store: AlertStore
	@Autowired private lateinit var frames: AnalyticsFrames
	@Autowired private lateinit var aggregator: UsageAggregator
	@Autowired private lateinit var boundaries: RetentionBoundaryReader

	@BeforeEach
	fun backfillDone() { SourceFixtures.completeBackfill() }

	private fun kst(dateTime: String): Instant = LocalDateTime.parse(dateTime).atZone(QueryReader.SEOUL).toInstant()

	private fun evaluator(clock: MutableClock) = AlertEvaluator(source, reader, store, frames, aggregator, organizations, boundaries,
		Duration.ofHours(1), Duration.ofMinutes(10), clock)

	private data class Org(val tenant: UUID, val owner: UUID, val member: UUID, val other: UUID)

	/** 사용량을 싣는 정책이 7월부터, 설치 하나가 8월 1일부터 [coveredUntil] 까지 손실 없이 덮인 조직. */
	private fun organization(coveredFrom: String = "2026-08-01T00:00:00", coveredUntil: String = "2026-09-20T00:00:00"): Org {
		val tenant = DashboardTestStores.insertTenant()
		SourceFixtures.setSummary(tenant, firstReceivedAt = kst("2026-08-01T00:00:00"), lastReceivedAt = kst("2026-09-20T00:00:00"))
		val owner = SourceFixtures.insertMember(tenant, "owner-${UUID.randomUUID()}@example.test", role = "owner")
		SourceFixtures.insertManifest(tenant, 1, owner, signals = """{"logs":true,"metrics":true,"traces":true}""", activatedAt = kst("2026-07-01T00:00:00"))
		val member = SourceFixtures.insertMember(tenant, "member-${UUID.randomUUID()}@example.test")
		val other = SourceFixtures.insertMember(tenant, "other-${UUID.randomUUID()}@example.test")
		val installation = SourceFixtures.insertInstallation(tenant, member)
		SourceFixtures.setInstallationTimes(installation, kst("2026-08-01T00:00:00"))
		SourceFixtures.insertSegment(installation, kst(coveredFrom), kst(coveredUntil))
		return Org(tenant, owner, member, other)
	}

	private fun enable(org: Org, ruleId: String, enabledAt: Instant, version: Long = 1) {
		DashboardTestStores.writer.sql("""INSERT INTO enrollment.organization_alert_rules (tenant_id,rule_id,enabled,version,updated_at,updated_by) VALUES (:t,:r,true,:v,:at,:by)
				ON CONFLICT (tenant_id, rule_id) DO UPDATE SET enabled = true, version = EXCLUDED.version, updated_at = EXCLUDED.updated_at""")
			.param("t", org.tenant).param("r", ruleId).param("v", version).param("at", Timestamp.from(enabledAt)).param("by", org.owner).update()
	}

	private fun register(org: Org, kind: String) {
		DashboardTestStores.writer.sql("INSERT INTO enrollment.managed_vendors (tenant_id,vendor_id,kind,source,created_at) VALUES (:t,:id,:kind,'manual',now())")
			.param("t", org.tenant).param("id", UUID.randomUUID().toString()).param("kind", kind).update()
	}

	private fun cost(org: Org, name: String, at: String, usd: String) = Event("${org.tenant}-$name", kst(at), memberId = org.member, sessionId = "s-$name",
		costEstimatedUsd = BigDecimal(usd), pricingVersion = "v1")

	private fun model(org: Org, name: String, at: String, model: String, member: UUID = org.member, product: String = "claude_code") = Event("${org.tenant}-$name", kst(at), memberId = member,
		sessionId = "s-$name", model = model, product = product, costEstimatedUsd = BigDecimal("0.01"), pricingVersion = "v1")

	private fun get(tenant: UUID, path: String): HttpResponse<String> = http.send(
		"/api/v1/organizations/$tenant$path",
		headers = mapOf("Authorization" to TestDashboardAuthenticator.header(DashboardPrincipal(tenant, UUID.randomUUID(), Role.ADMIN))),
	)

	private fun ok(tenant: UUID, path: String): JsonNode = get(tenant, path).also { assertThat(it.statusCode()).describedAs(it.body()).isEqualTo(200) }.let(DashboardHttp::json)

	private fun alerts(org: Org): List<StoredAlert> = store.alerts(org.tenant)

	private fun acknowledge(org: Org, alertId: UUID, version: Long) {
		DashboardTestStores.writer.sql("INSERT INTO enrollment.alert_acknowledgements (tenant_id,alert_id,alert_version,acknowledged_by,acknowledged_at) VALUES (:t,:a,:v,:by,now())")
			.param("t", org.tenant).param("a", alertId).param("v", version).param("by", org.owner).update()
	}

	@Test
	@DisplayName("급증 — 두 기간이 모두 완전하고 증가율이 0.4 이상이면 그날의 알림 하나, 다시 평가해도 하나, 0.4 미만이면 없다")
	fun spendSpike() {
		val spike = organization()
		val calm = organization()
		// 비교 기간(8/31~9/6) 10 → 현재 기간(9/7~9/13) 14: 정확히 0.4. 다른 조직은 13.99(0.399).
		SourceFixtures.insertEvents(spike.tenant, cost(spike, "before", "2026-09-03T10:00:00", "10"), cost(spike, "after", "2026-09-10T10:00:00", "14"))
		SourceFixtures.insertEvents(calm.tenant, cost(calm, "before", "2026-09-03T10:00:00", "10"), cost(calm, "after", "2026-09-10T10:00:00", "13.99"))
		// 9/14 0시 10분에 켰다 → 첫 평가일은 그 전날 9/13. 지금은 9/14 1시 30분 — 확정 대기(1시간)가 지난 날은 9/13 까지다.
		val clock = MutableClock(kst("2026-09-14T01:30:00"))
		for (org in listOf(spike, calm)) enable(org, "spend_spike", kst("2026-09-14T00:10:00"))
		evaluator(clock).evaluate(spike.tenant)
		evaluator(clock).evaluate(calm.tenant)

		val raised = alerts(spike).single()
		assertThat(listOf(raised.ruleId, raised.category, raised.windowKey, raised.subject, raised.status)).containsExactly("spend_spike", "cost", "2026-09-13", "", "closed")
		assertThat(raised.windowStart).isEqualTo(kst("2026-08-31T00:00:00"))
		assertThat(raised.windowEnd).isEqualTo(kst("2026-09-14T00:00:00"))
		assertThat(raised.occurredAt).isEqualTo(kst("2026-09-14T00:00:00"))
		assertThat(BigDecimal(raised.summary["currentCostUsd"].toString())).isEqualByComparingTo("14")
		assertThat(BigDecimal(raised.summary["previousCostUsd"].toString())).isEqualByComparingTo("10")
		assertThat(raised.summary["increaseRatio"]).isEqualTo(0.4)
		assertThat(raised.summary["currentStartDate"]).isEqualTo("2026-09-07")
		assertThat(raised.summary["previousEndDate"]).isEqualTo("2026-09-06")
		assertThat(alerts(calm)).isEmpty()
		assertThat(store.evaluation(calm.tenant, "spend_spike")!!.let { it.status to it.cursorDate.toString() }).isEqualTo("evaluated" to "2026-09-13")

		// 같은 날을 다시 평가해도 알림은 하나다(날이 이미 지나갔다 — 시작점이 다음 날이다).
		evaluator(clock).evaluate(spike.tenant)
		store.record(spike.tenant, store.evaluation(spike.tenant, "spend_spike")!!.copy(cursorDate = null))
		evaluator(clock).evaluate(spike.tenant)
		assertThat(alerts(spike)).hasSize(1)
	}

	@Test
	@DisplayName("급증의 전제가 깨지면 평가하지 않는다 — 기간이 완전하지 않음·앞 기간 0 은 사유를 남기고 알림을 만들지 않는다")
	fun spendSpikeNotEvaluated() {
		// 수집 구간이 9/10~9/12 만 덮는다 — 규칙은 켤 수 있지만 두 기간 모두 완전하지 않다.
		val partial = organization(coveredFrom = "2026-09-10T00:00:00", coveredUntil = "2026-09-12T00:00:00")
		SourceFixtures.insertEvents(partial.tenant, cost(partial, "before", "2026-09-03T10:00:00", "1"), cost(partial, "after", "2026-09-10T10:00:00", "100"))
		val idle = organization()
		SourceFixtures.insertEvents(idle.tenant, cost(idle, "after", "2026-09-10T10:00:00", "100"))
		val clock = MutableClock(kst("2026-09-14T01:30:00"))
		for (org in listOf(partial, idle)) {
			enable(org, "spend_spike", kst("2026-09-14T00:10:00"))
			evaluator(clock).evaluate(org.tenant)
			assertThat(alerts(org)).isEmpty()
		}
		assertThat(store.evaluation(partial.tenant, "spend_spike")!!.let { it.status to it.reason }).isEqualTo("not_evaluated" to "period_incomplete")
		assertThat(store.evaluation(idle.tenant, "spend_spike")!!.let { it.status to it.reason }).isEqualTo("not_evaluated" to "no_previous_spend")

		// 아직 확정 대기가 지난 날이 없다(켠 날의 전날이 확정 전).
		val early = organization()
		enable(early, "spend_spike", kst("2026-09-14T00:10:00"))
		evaluator(MutableClock(kst("2026-09-14T00:30:00"))).evaluate(early.tenant)
		assertThat(store.evaluation(early.tenant, "spend_spike")!!.let { it.status to it.reason }).isEqualTo("not_evaluated" to "period_not_settled")
	}

	@Test
	@DisplayName("미등록 제품 — 대상마다 24시간 안에 이어진 위반을 한 묶음으로 세고, 이어서 평가하면 열린 묶음이 늘며, 24시간 조용하면 닫힌다")
	fun unregisteredProductRolling() {
		val org = organization()
		register(org, "openai_biz")
		SourceFixtures.insertEvents(org.tenant,
			model(org, "allowed-1", "2026-09-10T09:00:00", "claude-sonnet-4", product = "codex"),
			model(org, "allowed-2", "2026-09-10T09:30:00", "gpt-5", product = "codex"),
			// 모델 이름이 달라도 등록 제품의 사용은 알림 대상이 아니다.
			model(org, "mini", "2026-09-10T11:00:00", "gpt-5-mini", product = "codex"),
			model(org, "opus-1", "2026-09-10T10:00:00", "claude-opus-4"),
			model(org, "opus-2", "2026-09-10T20:00:00", "claude-opus-4", member = org.other),
			model(org, "opus-3", "2026-09-11T19:00:00", "claude-opus-4"),
			model(org, "opus-4", "2026-09-13T10:00:00", "claude-opus-4"),
			// 켜기 24시간 전보다 앞선 위반은 보지 않는다.
			model(org, "old", "2026-09-08T10:00:00", "claude-opus-4"),
		)
		enable(org, "product_not_registered", kst("2026-09-10T00:00:00"))
		val clock = MutableClock(kst("2026-09-14T00:00:00"))
		evaluator(clock).evaluate(org.tenant)

		val opus = alerts(org).filter { it.subject == "claude_team" }.sortedBy { it.occurredAt }
		assertThat(opus.map { listOf(it.occurredAt, it.lastSeenAt, it.eventCount, it.status) }).containsExactly(
			listOf(kst("2026-09-10T10:00:00"), kst("2026-09-11T19:00:00"), 3L, "closed"),
			listOf(kst("2026-09-13T10:00:00"), kst("2026-09-13T10:00:00"), 1L, "open"),
		)
		assertThat(opus[0].memberIds).containsExactlyInAnyOrder(org.member.toString(), org.other.toString())
		assertThat(opus.map { it.category }).containsOnly("security")
		assertThat(alerts(org).map { it.subject }).containsOnly("claude_team")

		// 다음 회차: 열린 묶음에서 16시간 뒤의 위반은 같은 묶음이다(판이 오른다). 다시 평가해도 늘지 않는다.
		SourceFixtures.insertEvents(org.tenant, model(org, "opus-5", "2026-09-14T02:00:00", "claude-opus-4"))
		clock.now = kst("2026-09-14T04:00:00")
		evaluator(clock).evaluate(org.tenant)
		evaluator(clock).evaluate(org.tenant)
		val extended = alerts(org).single { it.subject == "claude_team" && it.status == "open" }
		assertThat(listOf(extended.eventCount, extended.version, extended.lastSeenAt)).containsExactly(2L, 2L, kst("2026-09-14T02:00:00"))
		assertThat(alerts(org).count { it.subject == "claude_team" }).isEqualTo(2)

		// 24시간 동안 위반이 없으면 닫힌다.
		clock.now = kst("2026-09-15T04:00:00")
		evaluator(clock).evaluate(org.tenant)
		assertThat(alerts(org).filter { it.subject == "claude_team" }.map { it.status }).containsOnly("closed")
	}

	@Test
	@DisplayName("도구 결과는 제품 사용으로 세지 않으며 꺼진 규칙은 평가하지 않는다")
	fun toolResultsAndDisabledRules() {
		val org = organization()
		register(org, "openai_biz")
		SourceFixtures.insertEvents(org.tenant,
			Event("${org.tenant}-bash", kst("2026-09-10T10:00:00"), usage = false, memberId = org.member, toolName = "Bash"),
			model(org, "usage", "2026-09-10T12:00:00", "claude-opus-4"),
			Event("${org.tenant}-mcp", kst("2026-09-10T11:00:00"), usage = false, memberId = org.member, toolName = "mcp_tool"),
		)
		// 제품 등록은 있지만 규칙이 꺼져 있다 — 평가도 알림도 없다.
		val clock = MutableClock(kst("2026-09-14T00:00:00"))
		evaluator(clock).evaluate(org.tenant)
		assertThat(store.evaluations(org.tenant)).isEmpty()
		assertThat(ok(org.tenant, "/analytics/overview?startDate=2026-09-07&endDate=2026-09-13").at("/alerts").let { it.path("availability").asString() to it.path("reason").asString() })
			.isEqualTo("unavailable" to "evaluation_not_configured")

		enable(org, "product_not_registered", kst("2026-09-10T00:00:00"))
		assertThat(ok(org.tenant, "/analytics/overview?startDate=2026-09-07&endDate=2026-09-13").at("/alerts/reason").asString()).isEqualTo("evaluation_pending")
		assertThat(evaluator(clock).runOnce()).isGreaterThanOrEqualTo(1)
		assertThat(alerts(org).map { it.ruleId to it.subject }).containsExactly("product_not_registered" to "claude_team")
	}

	@Test
	@DisplayName("개요·목록·단건 — 미확인은 확인 기록이 없는 알림이고, 확인하면 줄어든다. 다른 조직의 알림은 없다")
	fun overviewListAndAcknowledgement() {
		val org = organization()
		register(org, "cursor")
		SourceFixtures.insertEvents(org.tenant,
			cost(org, "before", "2026-09-03T10:00:00", "10"), cost(org, "after", "2026-09-10T10:00:00", "20").copy(product = "unknown"),
			model(org, "opus", "2026-09-10T12:00:00", "claude-opus-4"), model(org, "o3", "2026-09-11T12:00:00", "o3", product = "codex"))
		enable(org, "spend_spike", kst("2026-09-14T00:10:00"))
		enable(org, "product_not_registered", kst("2026-09-10T00:00:00"))
		val clock = MutableClock(kst("2026-09-14T01:30:00"))
		evaluator(clock).evaluate(org.tenant)
		val all = alerts(org)
		assertThat(all.map { it.ruleId }).containsExactlyInAnyOrder("spend_spike", "product_not_registered", "product_not_registered")

		val overview = ok(org.tenant, "/analytics/overview?startDate=2026-09-07&endDate=2026-09-13").at("/alerts")
		assertThat(listOf(overview.path("availability").asString(), overview.path("unacknowledgedTotal").asLong(), overview.path("security").asLong(), overview.path("cost").asLong()))
			.containsExactly("available", 3L, 2L, 1L)
		assertThat(overview.path("reason").isNull).isTrue()
		assertThat(Instant.parse(overview.path("asOf").asString())).isEqualTo(kst("2026-09-14T01:30:00"))

		val page = ok(org.tenant, "/alerts?limit=2")
		assertThat(page.at("/alerts/totalCount").asInt()).isEqualTo(3)
		assertThat(page.at("/alerts/items").size()).isEqualTo(2)
		val next = ok(org.tenant, "/alerts?limit=2&cursor=${page.at("/alerts/nextCursor").asString()}")
		val listed = (page.at("/alerts/items").toList() + next.at("/alerts/items").toList())
		// 최근 발생 순: 급증(9/14 0시) → o3(9/11 12시) → opus(9/10 12시).
		assertThat(listed.map { it.path("ruleId").asString() + ":" + it.path("subject").asString() }).containsExactly("spend_spike:", "product_not_registered:openai_biz", "product_not_registered:claude_team")
		assertThat(listed[0].path("subject").isNull && listed[0].path("eventCount").isNull).isTrue()
		assertThat(listed[1].path("members").toList().map { it.path("account").asString() }).allMatch { it.startsWith("member-") }
		assertThat(page.at("/evaluation/rules").toList().filter { it.path("enabled").asBoolean() }.map { it.path("ruleId").asString() to it.path("status").asString() })
			.containsExactlyInAnyOrder("spend_spike" to "evaluated", "product_not_registered" to "evaluated")

		val opus = all.single { it.subject == "claude_team" }
		acknowledge(org, opus.alertId, opus.version)
		val after = ok(org.tenant, "/analytics/overview?startDate=2026-09-07&endDate=2026-09-13").at("/alerts")
		assertThat(listOf(after.path("unacknowledgedTotal").asLong(), after.path("security").asLong(), after.path("cost").asLong())).containsExactly(2L, 1L, 1L)
		assertThat(ok(org.tenant, "/alerts?status=acknowledged").at("/alerts/items").toList().map { it.path("alertId").asString() }).containsExactly(opus.alertId.toString())
		assertThat(ok(org.tenant, "/alerts?category=cost").at("/alerts/items").toList().map { it.path("ruleId").asString() }).containsExactly("spend_spike")
		val detail = ok(org.tenant, "/alerts/${opus.alertId}").at("/alert")
		assertThat(detail.path("acknowledgement").path("acknowledgedBy").asString()).isEqualTo(org.owner.toString())
		assertThat(detail.path("version").asLong()).isEqualTo(opus.version)

		// 다른 조직의 경로로는 보이지 않는다.
		val stranger = organization()
		assertThat(get(stranger.tenant, "/alerts/${opus.alertId}").statusCode()).isEqualTo(404)
		assertThat(ok(stranger.tenant, "/alerts?status=all").at("/alerts/totalCount").asInt()).isZero()
		assertThat(get(org.tenant, "/alerts?status=closed").statusCode()).isEqualTo(400)
	}

	@Test
	@DisplayName("가용성 — 켠 규칙 가운데 지금 판의 평가가 없는 것이 있으면 evaluation_pending 이고, 모두 평가되면 available, 다시 켠 규칙은 평가될 때까지 pending, 모두 끄면 남은 기록으로 판단한다")
	fun availabilityWaitsForEveryEnabledRule() {
		val org = organization()
		register(org, "openai_biz")
		SourceFixtures.insertEvents(org.tenant, model(org, "opus", "2026-09-10T12:00:00", "claude-opus-4"))
		enable(org, "product_not_registered", kst("2026-09-10T00:00:00"))
		enable(org, "spend_spike", kst("2026-09-10T00:00:00"))
		val clock = MutableClock(kst("2026-09-14T01:30:00"))
		fun overview() = ok(org.tenant, "/analytics/overview?startDate=2026-09-07&endDate=2026-09-13").at("/alerts")
		fun evaluation() = ok(org.tenant, "/alerts?status=all").at("/evaluation")

		// 첫 회차 도중 — 한 규칙만 기록됐다. 아직 평가하지 않은 규칙을 0건으로 읽히게 두지 않는다(ADR 0051 §6).
		store.record(org.tenant, Evaluation("product_not_registered", 1, null, null, kst("2026-09-14T01:00:00"), "evaluated", null, null, null))
		with(overview()) {
			assertThat(listOf(path("availability").asString(), path("reason").asString())).containsExactly("unavailable", "evaluation_pending")
			assertThat(path("unacknowledgedTotal").isNull).isTrue()
			assertThat(Instant.parse(path("asOf").asString())).describedAs("평가 기록이 있으면 마지막 평가 시각").isEqualTo(kst("2026-09-14T01:00:00"))
		}
		with(evaluation()) {
			assertThat(path("availability").asString() to path("reason").asString()).isEqualTo("unavailable" to "evaluation_pending")
			assertThat(Instant.parse(path("asOf").asString())).describedAs("앞선 평가의 시각은 남긴다").isEqualTo(kst("2026-09-14T01:00:00"))
		}

		evaluator(clock).evaluate(org.tenant)
		with(overview()) {
			assertThat(listOf(path("availability").asString(), path("unacknowledgedTotal").asLong())).containsExactly("available", 1L)
			assertThat(path("reason").isNull).isTrue()
		}

		// 규칙을 다시 켜 판이 바뀌었다 — 그 판이 평가될 때까지 앞 판의 기록으로 0건을 말하지 않는다.
		enable(org, "spend_spike", kst("2026-09-14T01:40:00"), version = 3)
		assertThat(overview().path("reason").asString()).isEqualTo("evaluation_pending")
		assertThat(evaluation().path("rules").toList().single { it.path("ruleId").asString() == "spend_spike" }.path("evaluatedAt").isNull).isTrue()
		clock.advance(Duration.ofMinutes(15))
		evaluator(clock).evaluate(org.tenant)
		assertThat(overview().path("availability").asString()).isEqualTo("available")

		// 모두 끄면 켠 규칙이 없다 — 남은 평가 기록과 알림이 있으므로 미설정으로 숨기지 않는다.
		DashboardTestStores.writer.sql("UPDATE enrollment.organization_alert_rules SET enabled = false, version = version + 1 WHERE tenant_id = :t").param("t", org.tenant).update()
		with(overview()) {
			assertThat(listOf(path("availability").asString(), path("unacknowledgedTotal").asLong())).containsExactly("available", 1L)
		}
	}

	@Test
	@DisplayName("선점 — 다른 인스턴스가 기한 안에 잡은 조직은 평가하지 않고 기한이 지나면 가져가며, 같은 시각에 여럿이 잡으면 하나만 잡는다")
	fun evaluationLease() {
		val org = organization()
		register(org, "openai_biz")
		enable(org, "product_not_registered", kst("2026-09-10T00:00:00"))
		val clock = MutableClock(kst("2026-09-14T01:30:00"))
		val lease = Duration.ofMinutes(10)
		val mine = AlertEvaluator(source, reader, store, frames, aggregator, organizations, boundaries, Duration.ofHours(1), lease, clock, owner = "this-instance")

		assertThat(store.claim(org.tenant, "other-instance", clock.instant(), clock.instant().plus(lease))).isTrue()
		mine.runOnce()
		assertThat(store.evaluations(org.tenant)).describedAs("다른 인스턴스가 평가 중인 조직").isEmpty()
		clock.advance(lease)
		mine.runOnce()
		assertThat(store.evaluations(org.tenant)).describedAs("기한 시각에는 아직 그 인스턴스의 것").isEmpty()
		clock.advance(Duration.ofMillis(1))
		mine.runOnce()
		assertThat(store.evaluations(org.tenant).map { it.ruleId }).containsExactly("product_not_registered")
		assertThat(store.claim(org.tenant, "other-instance", clock.instant(), clock.instant().plus(lease))).describedAs("평가가 끝나면 선점을 놓는다").isTrue()

		// 같은 회차를 여러 인스턴스가 동시에 잡는다 — 정확히 하나만 잡는다.
		val contested = organization()
		val now = clock.instant()
		val start = CountDownLatch(1)
		val pool = Executors.newFixedThreadPool(8)
		try {
			val claims = (1..8).map { index -> pool.submit<Boolean> { start.await(); store.claim(contested.tenant, "instance-$index", now, now.plus(lease)) } }
			start.countDown()
			assertThat(claims.count { it.get(30, TimeUnit.SECONDS) }).isEqualTo(1)
		} finally {
			pool.shutdownNow()
		}
	}

	@Test
	@DisplayName("등록 제품은 모델과 무관하며 다른 조직의 등록·미분류·도구 결과·삭제 관측은 판정을 바꾸지 않는다")
	fun productBoundaries() {
		val org = organization()
		val other = organization()
		register(org, "claude_team")
		register(other, "openai_biz")
		SourceFixtures.insertEvents(org.tenant,
			model(org, "registered", "2026-09-10T09:00:00", "gpt-5", product = "claude_code"),
			model(org, "outside", "2026-09-10T10:00:00", "claude-opus-4", product = "codex"),
			model(org, "unknown", "2026-09-10T11:00:00", "gpt-5", product = "unknown"),
			model(org, "unmapped", "2026-09-10T11:10:00", "gpt-5", product = "new_tool"),
			model(org, "generic", "2026-09-10T11:20:00", "gpt-5", product = "codex").copy(mappingStatus = "generic"),
			model(org, "deleted", "2026-09-10T11:30:00", "gpt-5", product = "codex").copy(recordStatus = "tombstone"),
			model(org, "tool", "2026-09-10T11:40:00", "gpt-5", product = "codex").copy(usage = false, toolName = "Bash"),
		)
		enable(org, "product_not_registered", kst("2026-09-10T00:00:00"))
		val clock = MutableClock(kst("2026-09-10T14:00:00"))
		evaluator(clock).evaluate(org.tenant)
		val alert = alerts(org).single()
		assertThat(alert.subject to alert.eventCount).isEqualTo("openai_biz" to 1L)
		assertThat(alert.summary).containsEntry("productId", "openai_biz").containsKey("productName")
		register(org, "openai_biz")
		SourceFixtures.insertEvents(org.tenant, model(org, "now-registered", "2026-09-10T14:00:00", "new-model", product = "codex"))
		clock.now = kst("2026-09-10T16:00:00")
		evaluator(clock).evaluate(org.tenant)
		assertThat(alerts(org).single().eventCount).isEqualTo(1)
	}

	@Test
	@DisplayName("등록이 모두 없어지면 위반 0건으로 평가하지 않고 사유를 남긴다")
	fun noRegistrationIsNotEvaluated() {
		val org = organization()
		enable(org, "product_not_registered", kst("2026-09-10T00:00:00"))
		SourceFixtures.insertEvents(org.tenant, model(org, "outside", "2026-09-10T10:00:00", "gpt-5", product = "codex"))
		evaluator(MutableClock(kst("2026-09-10T14:00:00"))).evaluate(org.tenant)
		assertThat(alerts(org)).isEmpty()
		assertThat(store.evaluation(org.tenant, "product_not_registered")!!.let { it.status to it.reason })
			.isEqualTo("not_evaluated" to "registered_products_not_configured")
	}

}
