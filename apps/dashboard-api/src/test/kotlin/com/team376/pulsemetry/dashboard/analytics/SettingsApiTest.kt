package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.authentication.DashboardPrincipal
import com.team376.pulsemetry.dashboard.authentication.Role
import com.team376.pulsemetry.dashboard.config.AnalyticsConfig
import com.team376.pulsemetry.dashboard.config.DashboardApiProperties
import com.team376.pulsemetry.dashboard.request.PageCursorCodec
import com.team376.pulsemetry.dashboard.request.PageRequest
import com.team376.pulsemetry.dashboard.request.QueryReader
import com.team376.pulsemetry.dashboard.support.AbstractDashboardApiTest
import com.team376.pulsemetry.dashboard.support.DashboardHttp
import com.team376.pulsemetry.dashboard.support.DashboardTestStores
import com.team376.pulsemetry.dashboard.support.JsonStructure
import com.team376.pulsemetry.dashboard.support.SourceFixtures
import com.team376.pulsemetry.dashboard.support.SourceFixtures.Event
import com.team376.pulsemetry.dashboard.support.TestDashboardAuthenticator
import com.team376.pulsemetry.persistence.enrollment.management.VendorCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * 설정 화면의 현재 상태 조회를 HTTP 끝까지 (설정 명세). 기대값은 명세의 문장과 수용 사례에서 쓴다.
 */
class SettingsApiTest : AbstractDashboardApiTest() {

	// 안내 채널이 켜진 설정 서비스를 같은 컨텍스트의 부품으로 조립한다(컨텍스트를 새로 띄우지 않는다).
	@Autowired private lateinit var properties: DashboardApiProperties
	@Autowired private lateinit var source: JdbcClient
	@Autowired private lateinit var observations: VendorObservations
	@Autowired private lateinit var frames: AnalyticsFrames
	@Autowired private lateinit var tokens: CurrentStateTokens
	@Autowired private lateinit var codec: PageCursorCodec
	@Autowired private lateinit var mapper: ObjectMapper
	@Autowired private lateinit var clock: Clock
	@Autowired private lateinit var catalog: VendorCatalog
	@Autowired private lateinit var seats: SeatService

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

	private data class Org(val tenant: UUID, val applied: UUID, val outdated: UUID, val unknown: UUID, val registered: List<String>, val v2: UUID, val v3: UUID)

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
		val registered = listOf("claude_team", "openai_biz").map { kind ->
            val id = UUID.randomUUID().toString()
            DashboardTestStores.writer.sql("INSERT INTO enrollment.managed_vendors (tenant_id,vendor_id,kind,source,created_at) VALUES (:tenant,:id,:kind,'manual',now())")
                .param("tenant", tenant).param("id", id).param("kind", kind).update()
            val contract = if (kind == "claude_team") """{"version":1,"planId":"team","effectiveFrom":"2026-01-01","effectiveTo":null,"termNote":null,"tiers":[{"tierId":"standard","label":"표준","seats":2,"monthlyFeePerSeatUsd":"30"}],"monthlySeatFeeUsd":"60","confirmedAt":"2026-01-01T00:00:00Z","confirmedBy":"$admin"}""" else null
            DashboardTestStores.writer.sql("INSERT INTO enrollment.vendor_contract_versions (tenant_id,vendor_id,version,display_name,contract,recorded_at,recorded_by) VALUES (:tenant,:id,1,:kind,CAST(:contract AS jsonb),'2026-01-01',:admin)")
                .param("tenant", tenant).param("id", id).param("kind", kind).param("contract", contract, java.sql.Types.VARCHAR).param("admin", admin).update()
            id
        }
        return Org(tenant, applied, outdated, unknown, registered, v2, v3)
	}

	@Test
	@DisplayName("설정 — 수집 정책은 활성 manifest, 적용 확인이 없는 설치는 unknown, 저장한 적 없는 알림 규칙은 꺼짐·판 0 이고 근거가 없어 켤 수 없다")
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
		// 수집 구간 보고가 없고 한도 초과 근거는 없지만 제품은 등록되어 있다(허브 ADR 0008).
		assertThat(body.at("/alertRules").list().map { Triple(it.path("ruleId").asString(), it.path("enabled").asBoolean(), it.path("reason").asString()) })
			.containsExactly(
				Triple("spend_spike", false, "completeness_not_available"),
				Triple("quota_exceeded", false, "source_not_available"),
				Triple("product_not_registered", false, ""),
			)
		assertThat(body.at("/alertRules").list().map { it.path("version").asLong() }).containsOnly(0L)
		assertThat(body.at("/alertRules/2/availability").asString()).isEqualTo("available")
		assertThat(body.has("alertLists")).isFalse()
		assertThat(body.at("/alertRules/0/threshold/value").asDouble()).isEqualTo(0.4)
		assertThat(body.at("/alertRules/0/comparisonWindow").asString()).isEqualTo("preceding_7_calendar_days")
		assertThat(listOf("editContracts", "editCollectionPolicy", "editAlertRules", "notifyInstallations").map { body.at("/capabilities/$it").asBoolean() })
			.containsOnly(false)
		assertThat(body.at("/catalog/kinds").list().map { it.path("kind").asString() }).containsExactly("anthropic", "google", "openai")
		assertThat(body.at("/catalog/plans").size()).isZero()
		assertThat(body.at("/summary/configuredVendors").asLong()).isEqualTo(1)
		assertThat(body.at("/summary/unconfiguredVendors").asLong()).isEqualTo(1)
		assertThat(listOf("/summary/activeSeats7d", "/summary/meteredMonthToDate/data").map { body.at(it).isNull })
			.containsOnly(true)
		assertThat(body.at("/summary/monthlySeatFeeUsd").asString().toBigDecimal()).isEqualByComparingTo("60")
		assertThat(body.at("/summary/contractedSeats").asLong()).isEqualTo(2)
		assertThat(body.at("/ingest/status").asString()).isEqualTo("unknown")
	}

	@Test
	@DisplayName("온보딩의 원문 선택은 프롬프트와 응답이며 도구 내용과 이메일 설정은 별개다")
	fun rawContentFlag() {
		assertThat(ok(seed(privacy = """{"collect_tool_content":true}""").tenant, "/settings").at("/collectionPolicy/collectRawContent").asBoolean()).isFalse()
		assertThat(ok(seed(privacy = """{"collect_user_prompts":true}""").tenant, "/settings").at("/collectionPolicy/collectRawContent").asBoolean()).isTrue()
		assertThat(ok(seed(privacy = """{"collect_user_email":true}""").tenant, "/settings").at("/collectionPolicy/collectRawContent").asBoolean()).isFalse()
	}

	/** 이틀 전 정오(서울) — 최근 7·30일 창(기준일 전날까지) 안이다. */
	private fun recent(): Instant = LocalDate.now(QueryReader.SEOUL).minusDays(2).atTime(12, 0).atZone(QueryReader.SEOUL).toInstant()

	private fun observation(row: JsonNode) = listOf("firstSeenAt", "lastSeenAt", "activeUsers7d", "activeUsers30d", "observation").map { row.path(it).toString() }

	@Test
	@DisplayName("등록된 제품만 표시하고 관측은 카탈로그의 명시 매핑으로만 붙인다 — 레거시 공급자 계약은 포함하지 않는다")
	fun registeredProductsOnly() {
		val org = seed()
		val seen = recent()
		SourceFixtures.insertEvents(org.tenant, Event("${org.tenant}-codex", seen, product = "codex", serviceName = "codex-app-server", memberId = UUID.randomUUID()))
		val vendors = ok(org.tenant, "/vendors").at("/vendors/items").list().associateBy { it.path("vendorId").asString() }
		assertThat(vendors.keys).containsExactlyElementsOf(org.registered.sorted())
		val claude = vendors.getValue(org.registered[0])
		assertThat(claude.path("state").asString()).isEqualTo("configured")
		assertThat(claude.at("/contract/monthlySeatFeeUsd").asString()).isEqualTo("60")
		assertThat(claude.path("contractStatus").asString()).isEqualTo("active")
		val openai = vendors.getValue(org.registered[1])
		assertThat(openai.path("state").asString()).isEqualTo("needs_review")
		assertThat(openai.path("contract").isNull).isTrue()
		assertThat(openai.path("contractStatus").asString()).isEqualTo("missing")
		// codex 는 매핑으로 openai_biz 의 도구다(ADR 0044). 수집 구간 근거가 없어 창이 완전하지 않다 — 센 수만 부분 값으로 낸다.
		assertThat(observation(openai)).containsExactly("\"$seen\"", "\"$seen\"", "1", "1", "\"partial\"")
		// claude_team 은 매핑은 있으나 관측이 없다 — 0 이 아니라 모름이다.
		assertThat(observation(claude)).containsExactly("null", "null", "null", "null", "\"unobserved\"")
		assertThat(ok(org.tenant, "/vendors/${org.registered[0]}").at("/vendor/vendorId").asString()).isEqualTo(org.registered[0])
		assertThat(get(org.tenant, "/vendors/anthropic").statusCode()).isEqualTo(404)
	}

	@Test
	@DisplayName("같은 기준 시각의 설정 첫 화면·다음 페이지·상세는 같은 관측 값이다 — 관측 고정이 없는 기준 시각은 409")
	fun vendorObservationsAreFixedPerAsOf() {
		val org = seed()
		SourceFixtures.insertEvents(org.tenant, Event("${org.tenant}-a", recent(), memberId = UUID.randomUUID()))
		val settings = ok(org.tenant, "/settings")
		val snapshotId = settings.at("/meta/snapshotId").asString()
		val first = settings.at("/vendors/items").list().associate { it.path("vendorId").asString() to observation(it) }
		assertThat(first.getValue(org.registered[0])[2]).isEqualTo("1")
		// 고정 뒤에 들어온 관측은 같은 기준 시각의 값을 바꾸지 않는다.
		SourceFixtures.insertEvents(org.tenant, Event("${org.tenant}-b", recent(), memberId = UUID.randomUUID()))
		val listed = ok(org.tenant, "/vendors?snapshotId=$snapshotId").at("/vendors/items").list().associate { it.path("vendorId").asString() to observation(it) }
		assertThat(listed).isEqualTo(first)
		assertThat(observation(ok(org.tenant, "/vendors/${org.registered[0]}?snapshotId=$snapshotId").at("/vendor"))).isEqualTo(first.getValue(org.registered[0]))
		// 새 기준 시각은 다시 계산한다.
		assertThat(ok(org.tenant, "/vendors/${org.registered[0]}").at("/vendor/activeUsers7d").asLong()).isEqualTo(2)
		// 관측 고정이 없는 기준 시각(이전 판이 발급한 것)은 만료다 — 다시 계산해 섞지 않는다.
		val stale = tokens.issue(SettingsService.SETTINGS_KIND, org.tenant, clock.instant().minusSeconds(1)).value
		assertThat(get(org.tenant, "/vendors?snapshotId=$stale").statusCode()).isEqualTo(409)
		assertThat(get(org.tenant, "/vendors/${org.registered[0]}?snapshotId=$stale").statusCode()).isEqualTo(409)
		// 좌석 수를 관측 사용자 수로 채우지 않는다.
		assertThat(settings.at("/summary/activeSeats7d").isNull).isTrue()
	}

	@Test
	@DisplayName("관측됐지만 등록하지 않은 카탈로그 제품은 감지된 제품으로, 매핑 없는 관측은 어떤 제품에도 넣지 않고 따로 보인다")
	fun detectedAndUnmapped() {
		val tenant = DashboardTestStores.insertTenant()
		SourceFixtures.setSummary(tenant, firstReceivedAt = kst("2026-09-01T00:00:00"))
		val admin = SourceFixtures.insertMember(tenant, "admin@example.test", role = "admin")
		SourceFixtures.insertManifest(tenant, 1, admin)
		val seen = recent()
		SourceFixtures.insertEvents(tenant,
			Event("$tenant-codex", seen, product = "codex", serviceName = "codex-app-server", memberId = UUID.randomUUID(), semanticsProfile = null),
			Event("$tenant-unknown", seen, product = "unknown", serviceName = "some-tool", memberId = UUID.randomUUID(), semanticsProfile = null))

		val body = ok(tenant, "/settings")

		assertThat(body.at("/vendors/items").size()).isZero()
		val detected = body.at("/summary/detectedProducts").list()
		assertThat(detected.map { listOf(it.path("kind").asString(), it.path("displayName").asString(), it.path("state").asString()) })
			.containsExactly(listOf("openai_biz", "ChatGPT / Codex (OpenAI)", "detected_unconfigured"))
		assertThat(observation(detected.single())).containsExactly("\"$seen\"", "\"$seen\"", "1", "1", "\"partial\"")
		val unmapped = body.at("/summary/unmappedObservations")
		assertThat(unmapped.path("observedProducts").list().map { it.asString() }).containsExactly("unknown")
		assertThat(observation(unmapped)).containsExactly("\"$seen\"", "\"$seen\"", "1", "1", "\"partial\"")
		// 등록·계약 합계는 등록 제품만 센다.
		assertThat(listOf(body.at("/summary/configuredVendors").asLong(), body.at("/summary/unconfiguredVendors").asLong())).containsExactly(0L, 0L)
		// 관측이 없는 조직에는 감지된 제품도 매핑 없는 관측도 없다.
		val quiet = seed()
		val empty = ok(quiet.tenant, "/settings")
		assertThat(empty.at("/summary/detectedProducts").size()).isZero()
		assertThat(empty.at("/summary/unmappedObservations").isNull).isTrue()
	}

	@Test
	@DisplayName("벤더 페이지는 같은 snapshot ID 에서 이어지고 다른 목록의 cursor 는 400")
	fun vendorPaging() {
		val org = seed()

		val first = ok(org.tenant, "/vendors?limit=1")
		val snapshotId = first.at("/meta/snapshotId").asString()
		val second = ok(org.tenant, "/vendors?limit=1&snapshotId=$snapshotId&cursor=${first.at("/vendors/nextCursor").asString()}")
		assertThat((first.at("/vendors/items").list() + second.at("/vendors/items").list()).map { it.path("vendorId").asString() })
			.containsExactlyElementsOf(org.registered.sorted())

		val installations = ok(org.tenant, "/installations?limit=1")
		assertThat(get(org.tenant, "/vendors?limit=1&snapshotId=$snapshotId&cursor=${installations.at("/installations/nextCursor").asString()}").statusCode())
			.isEqualTo(400)
	}

	@Test
	@DisplayName("설치 — 등록 때의 버전, 확인된 적용 판, 마지막 설치 보고 시각(수신 시각을 넣지 않는다), 적용 상태 필터")
	fun installations() {
		val org = seed()
		SourceFixtures.insertLedger(org.tenant, org.unknown, Instant.now().minusSeconds(30))
		val reported = kst("2026-09-28T10:00:00")
		SourceFixtures.setHeartbeat(org.outdated, reported, appliedManifestId = org.v2)

		val all = ok(org.tenant, "/installations")
		val rows = all.at("/installations/items").list().associateBy { it.path("installationId").asString() }
		assertThat(rows.keys).containsExactlyInAnyOrder(org.applied.toString(), org.outdated.toString(), org.unknown.toString())
		assertThat(rows.getValue(org.applied.toString()).path("agentVersion").asString()).isEqualTo("0.9.1")
		assertThat(rows.getValue(org.applied.toString()).path("appliedPolicyVersion").asLong()).isEqualTo(3)
		assertThat(rows.getValue(org.outdated.toString()).path("appliedPolicyVersion").asLong()).isEqualTo(2)
		assertThat(rows.getValue(org.unknown.toString()).path("appliedPolicyVersion").isNull).isTrue()
		// 마지막 설치 보고의 서버 수신 시각이다. 보고가 없는 설치는 데이터를 받았어도 null 이다.
		assertThat(rows.getValue(org.outdated.toString()).path("lastHeartbeatAt").asString()).isEqualTo(reported.toString())
		assertThat(listOf(org.applied, org.unknown).map { rows.getValue(it.toString()).path("lastHeartbeatAt").isNull }).containsOnly(true)
		// 안내 채널이 없는 배포(이 컨텍스트)에서는 아무 설치에도 안내할 수 없다.
		assertThat(rows.values.map { it.path("canNotify").asBoolean() }).containsOnly(false)
		assertThat(rows.getValue(org.applied.toString()).at("/team/teamName").asString()).isEqualTo("플랫폼")
		assertThat(all.at("/desiredPolicyVersion").asLong()).isEqualTo(3)

		fun filtered(status: String) = ok(org.tenant, "/installations?policyStatus=$status").at("/installations/items").list().map { it.path("installationId").asString() }
		assertThat(filtered("outdated")).containsExactly(org.outdated.toString())
		assertThat(filtered("applied")).containsExactly(org.applied.toString())
		assertThat(filtered("unknown")).containsExactly(org.unknown.toString())
		assertThat(ok(org.tenant, "/installations?policyStatus=unknown").at("/installations/totalCount").asInt()).isEqualTo(1)
		assertThat(get(org.tenant, "/installations?policyStatus=revoked").statusCode()).isEqualTo(400)
		assertThat(get(org.tenant, "/installations?policyStatus=").statusCode()).isEqualTo(400)

		// 필터마다 cursor 의 범위가 다르다 — 다른 필터의 cursor 는 400 이다.
		val first = ok(org.tenant, "/installations?limit=1")
		val cursor = first.at("/installations/nextCursor").asString()
		val snapshotId = first.at("/meta/snapshotId").asString()
		assertThat(get(org.tenant, "/installations?limit=1&snapshotId=$snapshotId&cursor=$cursor").statusCode()).isEqualTo(200)
		assertThat(get(org.tenant, "/installations?limit=1&policyStatus=applied&snapshotId=$snapshotId&cursor=$cursor").statusCode()).isEqualTo(400)
	}

	@Test
	@DisplayName("적용 판의 근거 — 보고가 있으면 heartbeat·확인 시각 null, 보고 없이 확인 기록만 있으면 applied_confirmation·고른 판의 확인 시각, 둘 다 없으면 none. 요약도 같은 근거로 센다")
	fun appliedEvidence() {
		val org = seed()
		val admin = SourceFixtures.insertMember(org.tenant, "evidence@example.test")
		// 확인 기록이 있지만 보고하는 설치 — 근거는 보고다(서버가 모르는 판을 보고해도).
		val reported = SourceFixtures.insertInstallation(org.tenant, admin)
		SourceFixtures.insertAssignment(reported, org.v3, kst("2026-09-10T00:00:00"))
		SourceFixtures.setHeartbeat(reported, kst("2026-09-28T10:00:00"))
		// 더 낮은 판(v2)을 나중에 확인한 기록이 있는 설치 — 고른 판(v3)의 확인 시각이다.
		val confirmedTwice = SourceFixtures.insertInstallation(org.tenant, admin)
		SourceFixtures.insertAssignment(confirmedTwice, org.v3, kst("2026-09-11T00:00:00"))
		SourceFixtures.insertAssignment(confirmedTwice, org.v2, kst("2026-09-20T00:00:00"))

		val rows = ok(org.tenant, "/installations").at("/installations/items").list().associateBy { it.path("installationId").asString() }
		fun evidence(id: UUID) = rows.getValue(id.toString()).let { it.path("appliedEvidence").asString() to it.path("appliedConfirmedAt").let { at -> if (at.isNull) null else at.asString() } }
		assertThat(evidence(reported)).isEqualTo("heartbeat" to null)
		assertThat(rows.getValue(reported.toString()).path("appliedPolicyVersion").isNull).isTrue()
		assertThat(evidence(org.applied)).isEqualTo("applied_confirmation" to kst("2026-09-10T00:00:00").toString())
		// v3 은 확인 시각이 없는 기록뿐이라 고른 판은 v2 이고 그 확인 시각이다.
		assertThat(evidence(org.outdated)).isEqualTo("applied_confirmation" to kst("2026-08-10T00:00:00").toString())
		assertThat(evidence(org.unknown)).isEqualTo("none" to null)
		assertThat(evidence(confirmedTwice)).isEqualTo("applied_confirmation" to kst("2026-09-11T00:00:00").toString())
		assertThat(rows.getValue(confirmedTwice.toString()).path("appliedPolicyVersion").asLong()).isEqualTo(3)

		val rollout = ok(org.tenant, "/settings").at("/policyRollout")
		assertThat(listOf("heartbeat", "appliedConfirmation", "none").map { rollout.at("/evidence/$it").asLong() }).containsExactly(1L, 3L, 1L)
		assertThat(rollout.path("eligibleInstallations").asLong()).isEqualTo(5)
		// 목록의 근거와 요약의 근거별 수가 같다.
		assertThat(rows.values.groupingBy { it.path("appliedEvidence").asString() }.eachCount())
			.isEqualTo(mapOf("heartbeat" to 1, "applied_confirmation" to 3, "none" to 1))
	}

	@Test
	@DisplayName("적용 판은 설치가 지금 집행하는 판이다 — 보고가 있으면 마지막 보고의 판, 적용한 적이 있는 가장 높은 판이 아니다")
	fun appliedVersionIsTheReportedOne() {
		val org = seed()
		val admin = SourceFixtures.insertMember(org.tenant, "rollback@example.test")
		// v3 을 적용했다고 확인받은 뒤 v2 로 돌아가 보고하는 설치, 서버가 모르는 판을 보고하는 설치.
		val rolledBack = SourceFixtures.insertInstallation(org.tenant, admin)
		val strange = SourceFixtures.insertInstallation(org.tenant, admin)
		for (installation in listOf(rolledBack, strange)) SourceFixtures.insertAssignment(installation, org.v3, kst("2026-09-10T00:00:00"))
		SourceFixtures.setHeartbeat(rolledBack, kst("2026-09-28T10:00:00"), appliedManifestId = org.v2)
		SourceFixtures.setHeartbeat(strange, kst("2026-09-28T10:00:00"))
		// 보고한 적 없는 설치는 적용 확인 기록을 쓴다.
		SourceFixtures.setHeartbeat(org.applied, kst("2026-09-28T10:00:00"), appliedManifestId = org.v3)

		val rows = ok(org.tenant, "/installations").at("/installations/items").list().associateBy { it.path("installationId").asString() }
		assertThat(rows.getValue(rolledBack.toString()).path("appliedPolicyVersion").asLong()).isEqualTo(2)
		assertThat(rows.getValue(strange.toString()).path("appliedPolicyVersion").isNull).isTrue()
		assertThat(rows.getValue(org.applied.toString()).path("appliedPolicyVersion").asLong()).isEqualTo(3)
		assertThat(rows.getValue(org.outdated.toString()).path("appliedPolicyVersion").asLong()).isEqualTo(2)
		val rollout = ok(org.tenant, "/settings").at("/policyRollout")
		assertThat(listOf("eligibleInstallations", "appliedInstallations", "outdatedInstallations", "unknownInstallations").map { rollout.path(it).asLong() })
			.containsExactly(5L, 1L, 2L, 2L)
	}

	@Test
	@DisplayName("알림 규칙 — 등록 제품의 가용성과 판을 내고 폐기한 규칙·목록은 응답하지 않는다")
	fun storedAlertRules() {
		val org = seed()
		val other = seed()
		val admin = DashboardTestStores.writer.sql("SELECT id FROM enrollment.members WHERE tenant_id = :t LIMIT 1").param("t", org.tenant).query(UUID::class.java).single()
		DashboardTestStores.writer.sql("INSERT INTO enrollment.organization_alert_rules (tenant_id,rule_id,enabled,version,updated_at,updated_by) VALUES (:t,'product_not_registered',true,3,'2026-09-20T02:00:00Z',:a)")
			.param("t", org.tenant).param("a", admin).update()
		// 다른 조직의 수집 구간은 이 조직의 근거가 아니다.
		SourceFixtures.insertSegment(other.applied, kst("2026-09-20T00:00:00"), kst("2026-09-20T01:00:00"))

		val before = ok(org.tenant, "/settings")
		val rules = before.at("/alertRules").list().associateBy { it.path("ruleId").asString() }
		assertThat(rules.getValue("product_not_registered").let { listOf(it.path("enabled").asBoolean(), it.path("version").asLong(), it.path("availability").asString(), it.path("reason").isNull) })
			.containsExactly(true, 3L, "available", true)
		assertThat(rules.keys).containsExactlyInAnyOrder("spend_spike", "quota_exceeded", "product_not_registered")
		assertThat(rules.getValue("spend_spike").path("reason").asString()).isEqualTo("completeness_not_available")
		assertThat(before.has("alertLists")).isFalse()

		// 이 조직의 설치가 수집 구간을 보고하면 급증 규칙을 켤 수 있다(완전한 날이 생길 근거).
		SourceFixtures.insertSegment(org.applied, kst("2026-09-20T00:00:00"), kst("2026-09-20T01:00:00"))
		val after = ok(org.tenant, "/settings").at("/alertRules").list().associateBy { it.path("ruleId").asString() }
		assertThat(after.getValue("spend_spike").let { it.path("availability").asString() to it.path("reason").isNull }).isEqualTo("available" to true)
		assertThat(after.getValue("spend_spike").path("enabled").asBoolean()).isFalse()
		// 한도 초과는 근거가 없다.
		assertThat(after.getValue("quota_exceeded").path("reason").asString()).isEqualTo("source_not_available")
	}

	@Test
	@DisplayName("알림 규칙 편집 — 관리 기능이 켜진 배포에서만 할 수 있다")
	fun alertRuleCapability() {
		val org = seed()
		val organization = requireNotNull(organizations.find(org.tenant))
		fun service(management: Boolean) = AnalyticsConfig().settingsService(management, false, properties, source, observations, frames, tokens, codec,
			mapper, clock, catalog, seats)
		assertThat(service(management = true).settings(organization).capabilities.editAlertRules).isTrue()
		assertThat(service(management = false).settings(organization).capabilities.editAlertRules).isFalse()
	}

	@Test
	@DisplayName("안내 채널이 있는 배포 — 구성원이 활성이고 아직 적용이 확인되지 않은 설치만 안내할 수 있다")
	fun notificationChannel() {
		val org = seed()
		val suspended = SourceFixtures.insertMember(org.tenant, "suspended@example.test", status = "suspended")
		val parked = SourceFixtures.insertInstallation(org.tenant, suspended)
		val organization = requireNotNull(organizations.find(org.tenant))
		// 앱 조립과 같은 경로로 만든다 — 관리 기능과 메일이 모두 켜져야 채널이 있다.
		fun service(management: Boolean, mail: Boolean) = AnalyticsConfig().settingsService(management, mail, properties, source, observations, frames, tokens, codec,
			mapper, clock, catalog, seats)
		fun notifiable(service: SettingsService) = service.installations(organization, null, PageRequest(100, null), null).installations.items
			.associate { it.installationId to it.canNotify }

		val open = service(management = true, mail = true)
		assertThat(open.settings(organization).capabilities.notifyInstallations).isTrue()
		assertThat(notifiable(open)).isEqualTo(mapOf(org.applied.toString() to false, org.outdated.toString() to true, org.unknown.toString() to true,
			parked.toString() to false))
		for (closed in listOf(service(management = true, mail = false), service(management = false, mail = true))) {
			assertThat(closed.settings(organization).capabilities.notifyInstallations).isFalse()
			assertThat(notifiable(closed).values).containsOnly(false)
		}
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

		JsonStructure.assertMatches("settings-response.example.json", ok(org.tenant, "/settings"))
	}
    @Test fun expiredContractPreservesDetailsAndHasZeroActiveSummary() {
        val org = seed()
        DashboardTestStores.writer.sql("UPDATE enrollment.vendor_contract_versions SET contract=jsonb_set(contract, '{effectiveTo}', '\"2026-01-02\"'::jsonb) WHERE tenant_id=:tenant AND vendor_id=:id")
            .param("tenant", org.tenant).param("id", org.registered[0]).update()
        val vendor = ok(org.tenant, "/vendors/${org.registered[0]}").path("vendor")
        assertThat(vendor.path("contractStatus").asString()).isEqualTo("expired")
        assertThat(vendor.path("state").asString()).isEqualTo("needs_review")
        assertThat(vendor.path("contract").path("monthlySeatFeeUsd").asString()).isEqualTo("60")
        val settings = ok(org.tenant, "/settings")
        assertThat(settings.at("/summary/monthlySeatFeeUsd").asString().toBigDecimal()).isEqualByComparingTo("0")
        assertThat(settings.at("/summary/contractedSeats").asLong()).isZero()
        assertThat(settings.at("/vendors/items").list().single { it.path("vendorId").asString() == org.registered[0] }.path("contractStatus").asString()).isEqualTo("expired")
    }

    @Test fun activeSummaryExcludesExpiredAndScheduledContracts() {
        val org = seed()
        // Claude is active; copy its contract to the other product with a different validity period.
        for (dates in listOf(
            "{\"effectiveFrom\":\"2025-01-01\",\"effectiveTo\":\"2025-12-31\"}",
            "{\"effectiveFrom\":\"2099-01-01\",\"effectiveTo\":null}"
        )) {
            DashboardTestStores.writer.sql("UPDATE enrollment.vendor_contract_versions target SET contract=(SELECT contract FROM enrollment.vendor_contract_versions WHERE tenant_id=:tenant AND vendor_id=:source) || CAST(:dates AS jsonb) WHERE target.tenant_id=:tenant AND target.vendor_id=:target")
                .param("tenant", org.tenant).param("source", org.registered[0]).param("target", org.registered[1]).param("dates", dates).update()
            val settings = ok(org.tenant, "/settings")
            assertThat(settings.at("/summary/monthlySeatFeeUsd").asString().toBigDecimal()).isEqualByComparingTo("60")
            assertThat(settings.at("/summary/contractedSeats").asLong()).isEqualTo(2)
            assertThat(settings.at("/summary/unconfiguredVendors").asLong()).isEqualTo(1)
        }
    }

    @Test fun emptyRegistrationsHaveZeroActiveSummary() {
        val org = seed()
        DashboardTestStores.writer.sql("UPDATE enrollment.vendor_contract_versions SET archived=true WHERE tenant_id=:tenant")
            .param("tenant", org.tenant).update()
        val settings = ok(org.tenant, "/settings")
        assertThat(settings.at("/summary/monthlySeatFeeUsd").asString().toBigDecimal()).isEqualByComparingTo("0")
        assertThat(settings.at("/summary/contractedSeats").asLong()).isZero()
    }

}
