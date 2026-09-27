package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.authentication.DashboardPrincipal
import com.team376.pulsemetry.dashboard.authentication.Role
import com.team376.pulsemetry.dashboard.request.QueryReader
import com.team376.pulsemetry.dashboard.support.AbstractDashboardApiTest
import com.team376.pulsemetry.dashboard.support.DashboardHttp
import com.team376.pulsemetry.dashboard.support.DashboardTestStores
import com.team376.pulsemetry.dashboard.support.JsonStructure
import com.team376.pulsemetry.dashboard.support.SourceFixtures
import com.team376.pulsemetry.dashboard.support.SourceFixtures.Event
import com.team376.pulsemetry.dashboard.support.TestDashboardAuthenticator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.net.http.HttpResponse
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

/**
 * 설정 화면의 현재 상태 조회를 HTTP 끝까지 (설정 명세). 기대값은 명세의 문장과 수용 사례에서 쓴다.
 */
class SettingsApiTest : AbstractDashboardApiTest() {

	@BeforeEach
	fun backfillDone() {
		SourceFixtures.completeBackfill()
	}

	private fun kst(dateTime: String): Instant = LocalDateTime.parse(dateTime).atZone(QueryReader.SEOUL).toInstant()

	private fun get(tenant: UUID, path: String, role: Role = Role.ADMIN): HttpResponse<String> = http.send(
		"/api/v1/organizations/$tenant$path",
		headers = mapOf("Authorization" to TestDashboardAuthenticator.header(DashboardPrincipal(tenant, UUID.randomUUID(), role))),
	)

	private fun ok(tenant: UUID, path: String): JsonNode {
		val response = get(tenant, path)
		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200)
		return DashboardHttp.json(response)
	}

	private fun JsonNode.list(): List<JsonNode> = (0 until size()).map { get(it) }

	private data class Org(val tenant: UUID, val applied: UUID, val outdated: UUID, val unknown: UUID)

	/** 활성 manifest v3(원문 수집 꺼짐), 옛 v2. 설치 셋 — v3 적용 확인·v2 적용 확인·적용 보고 없음. anthropic 현재 계약, openai 만료 계약. */
	private fun seed(privacy: String = """{"collect_user_prompts":false,"collect_user_email":true}"""): Org {
		val tenant = DashboardTestStores.insertTenant()
		SourceFixtures.setSummary(tenant, firstReceivedAt = kst("2026-09-01T00:00:00"))
		val admin = SourceFixtures.insertMember(tenant, "admin@example.test", role = "admin")
		val team = SourceFixtures.insertTeam(tenant, "플랫폼")
		SourceFixtures.insertMembership(team, admin, kst("2026-01-01T00:00:00"))
		val v2 = SourceFixtures.insertManifest(tenant, 2, admin, active = false)
		val v3 = SourceFixtures.insertManifest(tenant, 3, admin, privacy = privacy)
		val applied = SourceFixtures.insertInstallation(tenant, admin, clientVersion = "0.9.1")
		val outdated = SourceFixtures.insertInstallation(tenant, admin, clientVersion = "0.8.0")
		val unknown = SourceFixtures.insertInstallation(tenant, admin)
		SourceFixtures.insertInstallation(tenant, admin, status = "revoked")
		SourceFixtures.insertAssignment(applied, v3, kst("2026-09-10T00:00:00"))
		SourceFixtures.insertAssignment(outdated, v2, kst("2026-08-10T00:00:00"))
		SourceFixtures.insertAssignment(outdated, v3, null)
		SourceFixtures.insertAssignment(unknown, v3, null)
		SourceFixtures.insertContract(tenant, "anthropic")
		SourceFixtures.insertContract(tenant, "openai", status = "expired", startsAt = "2025-01-01", endsAt = "2025-12-31")
		return Org(tenant, applied, outdated, unknown)
	}

	@Test
	@DisplayName("설정 — 수집 정책은 활성 manifest, 적용 확인이 없는 설치는 unknown, 알림 규칙은 초기 제안 기준으로 비활성")
	fun settings() {
		val org = seed()

		val body = ok(org.tenant, "/settings")

		assertThat(body.at("/collectionPolicy/version").asLong()).isEqualTo(3)
		assertThat(body.at("/collectionPolicy/collectRawContent").asBoolean()).isFalse()
		assertThat(body.at("/collectionPolicy/reclaimIdleDays").asInt()).isEqualTo(14)
		assertThat(listOf("/collectionPolicy/aggregateRetentionMonths", "/collectionPolicy/rawContentRetentionDays").map { body.at(it).isNull }).containsOnly(true)
		assertThat(body.at("/policyRollout/desiredVersion").asLong()).isEqualTo(3)
		assertThat(body.at("/policyRollout/eligibleInstallations").asLong()).isEqualTo(3)
		assertThat(body.at("/policyRollout/appliedInstallations").asLong()).isEqualTo(1)
		assertThat(body.at("/policyRollout/outdatedInstallations").asLong()).isEqualTo(1)
		assertThat(body.at("/policyRollout/unknownInstallations").asLong()).isEqualTo(1)
		assertThat(body.at("/alertRules").list().map { Triple(it.path("ruleId").asString(), it.path("enabled").asBoolean(), it.path("reason").asString()) })
			.containsExactly(
				Triple("spend_spike", false, "evaluation_not_configured"),
				Triple("quota_exceeded", false, "source_not_available"),
				Triple("model_not_allowed", false, "evaluation_not_configured"),
				Triple("tool_unapproved", false, "evaluation_not_configured"),
			)
		assertThat(body.at("/alertRules/0/threshold/value").asDouble()).isEqualTo(0.4)
		assertThat(body.at("/alertRules/0/comparisonWindow").asString()).isEqualTo("preceding_7_calendar_days")
		assertThat(listOf("editContracts", "editCollectionPolicy", "editAlertRules", "notifyInstallations").map { body.at("/capabilities/$it").asBoolean() })
			.containsOnly(false)
		assertThat(body.at("/catalog/kinds").list().map { it.path("kind").asString() }).containsExactly("anthropic", "google", "openai")
		assertThat(body.at("/catalog/plans").size()).isZero()
		assertThat(body.at("/summary/configuredVendors").asLong()).isEqualTo(1)
		assertThat(body.at("/summary/unconfiguredVendors").asLong()).isEqualTo(1)
		assertThat(listOf("/summary/monthlySeatFeeUsd", "/summary/contractedSeats", "/summary/activeSeats7d", "/summary/meteredMonthToDate/data").map { body.at(it).isNull })
			.containsOnly(true)
		assertThat(body.at("/ingest/status").asString()).isEqualTo("unknown")
	}

	@Test
	@DisplayName("원문류 수집 설정이 하나라도 켜져 있으면 collectRawContent 다 — 이메일 수집은 원문이 아니다")
	fun rawContentFlag() {
		assertThat(ok(seed(privacy = """{"collect_tool_content":true}""").tenant, "/settings").at("/collectionPolicy/collectRawContent").asBoolean()).isTrue()
		assertThat(ok(seed(privacy = """{"collect_user_email":true}""").tenant, "/settings").at("/collectionPolicy/collectRawContent").asBoolean()).isFalse()
	}

	@Test
	@DisplayName("벤더 — 계약에서 오고 계약 모델이 맞지 않아 contract 는 null + 확인 코드, 사례 21: 근거 없는 Codex 사용은 OpenAI 지표에 들어가지 않는다")
	fun vendorsFromContracts() {
		val org = seed()
		SourceFixtures.insertEvents(
			org.tenant,
			Event("${org.tenant}-codex", Instant.now().minusSeconds(3600), product = "codex", serviceName = "codex-app-server", memberId = UUID.randomUUID()),
		)

		val vendors = ok(org.tenant, "/vendors").at("/vendors/items").list().associateBy { it.path("vendorId").asString() }

		assertThat(vendors.keys).containsExactly("anthropic", "openai")
		val anthropic = vendors.getValue("anthropic")
		assertThat(anthropic.path("state").asString()).isEqualTo("configured")
		assertThat(anthropic.path("source").asString()).isEqualTo("manual")
		assertThat(anthropic.path("contract").isNull).isTrue()
		assertThat(anthropic.at("/checks/0/code").asString()).isEqualTo("contract_model_unsupported")
		assertThat(anthropic.at("/meteredMonthToDate/availability").asString()).isEqualTo("unavailable")
		val openai = vendors.getValue("openai")
		assertThat(openai.path("state").asString()).isEqualTo("needs_review")
		assertThat(listOf("firstSeenAt", "lastSeenAt", "activeUsers7d", "activeUsers30d").map { openai.path(it).isNull }).containsOnly(true)
		assertThat(openai.path("observation").asString()).isEqualTo("unobserved")

		assertThat(ok(org.tenant, "/vendors/anthropic").at("/vendor/vendorId").asString()).isEqualTo("anthropic")
		assertThat(get(org.tenant, "/vendors/google").statusCode()).isEqualTo(404)
	}

	@Test
	@DisplayName("벤더 페이지는 같은 snapshot ID 에서 이어지고 다른 목록의 cursor 는 400")
	fun vendorPaging() {
		val org = seed()

		val first = ok(org.tenant, "/vendors?limit=1")
		val snapshotId = first.at("/meta/snapshotId").asString()
		val second = ok(org.tenant, "/vendors?limit=1&snapshotId=$snapshotId&cursor=${first.at("/vendors/nextCursor").asString()}")
		assertThat((first.at("/vendors/items").list() + second.at("/vendors/items").list()).map { it.path("vendorId").asString() })
			.containsExactly("anthropic", "openai")

		val installations = ok(org.tenant, "/installations?limit=1")
		assertThat(get(org.tenant, "/vendors?limit=1&snapshotId=$snapshotId&cursor=${installations.at("/installations/nextCursor").asString()}").statusCode())
			.isEqualTo(400)
	}

	@Test
	@DisplayName("설치 — 등록 때의 버전, 확인된 적용 판, heartbeat 는 원천이 없어 null(수신 시각을 넣지 않는다), outdated 는 알려진 판이 낮은 설치만")
	fun installations() {
		val org = seed()
		SourceFixtures.insertLedger(org.tenant, org.unknown, Instant.now().minusSeconds(30))

		val all = ok(org.tenant, "/installations")
		val rows = all.at("/installations/items").list().associateBy { it.path("installationId").asString() }
		assertThat(rows.keys).containsExactlyInAnyOrder(org.applied.toString(), org.outdated.toString(), org.unknown.toString())
		assertThat(rows.getValue(org.applied.toString()).path("agentVersion").asString()).isEqualTo("0.9.1")
		assertThat(rows.getValue(org.applied.toString()).path("appliedPolicyVersion").asLong()).isEqualTo(3)
		assertThat(rows.getValue(org.outdated.toString()).path("appliedPolicyVersion").asLong()).isEqualTo(2)
		assertThat(rows.getValue(org.unknown.toString()).path("appliedPolicyVersion").isNull).isTrue()
		assertThat(rows.values.map { it.path("lastHeartbeatAt").isNull }).containsOnly(true)
		assertThat(rows.values.map { it.path("canNotify").asBoolean() }).containsOnly(false)
		assertThat(rows.getValue(org.applied.toString()).at("/team/teamName").asString()).isEqualTo("플랫폼")
		assertThat(all.at("/desiredPolicyVersion").asLong()).isEqualTo(3)

		val outdated = ok(org.tenant, "/installations?policyStatus=outdated")
		assertThat(outdated.at("/installations/items").list().map { it.path("installationId").asString() }).containsExactly(org.outdated.toString())
		assertThat(get(org.tenant, "/installations?policyStatus=unknown").statusCode()).isEqualTo(400)
	}

	@Test
	@DisplayName("활성 manifest 가 없는 조직은 설정이 없다(404) — 값을 지어내지 않는다")
	fun noManifestIs404() {
		val tenant = DashboardTestStores.insertTenant()

		assertThat(get(tenant, "/settings").statusCode()).isEqualTo(404)
		assertThat(get(tenant, "/installations").statusCode()).isEqualTo(404)
		assertThat(ok(tenant, "/vendors").at("/vendors/totalCount").asInt()).isZero()
	}

	@Test
	@DisplayName("설정 권한이 없으면 403")
	fun requiresPermission() {
		val org = seed()

		for (path in listOf("/settings", "/vendors", "/vendors/anthropic", "/installations")) {
			assertThat(get(org.tenant, path, Role.VIEWER).statusCode()).describedAs(path).isEqualTo(403)
		}
	}

	@Test
	@DisplayName("응답의 키 구조가 요청서의 예시 JSON 과 같다")
	fun structureMatchesExample() {
		val org = seed()

		JsonStructure.assertSameKeys("", JsonStructure.example("settings-response.example.json"), ok(org.tenant, "/settings"))
	}
}
