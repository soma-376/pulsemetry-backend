package com.team376.pulsemetry.connector.vendor

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 커넥터 둘(ADR 0054)을 모의 벤더 서버로 검증한다. 모의 응답은 `docs/vendor-connector-evidence.md` 의 요청·응답 예시를 그대로 재현한다(이메일만 `@example.test`로).
 * 기대값은 그 문서와 ADR 0048 §8(실패 분류·요청 성공 ≠ 완료)에서 쓴다 — 여러 페이지, 429 뒤 재시도, 401·403, 5xx, 빈 목록, 알 수 없는 필드.
 */
class VendorConnectorsTest {
	private val server = MockVendorServer()
	private val waits = CopyOnWriteArrayList<Duration>()
	private val clock = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC)
	private val policy = HttpPolicy(requestTimeout = Duration.ofSeconds(5), maxAttempts = 3, retryBackoff = Duration.ofSeconds(2), maxRetryWait = Duration.ofSeconds(30))
	private val http = VendorHttp(policy, clock = clock, sleep = { waits += it })
	private val secret = "fake-vendor-credential-" + "k".repeat(20)

	@AfterEach fun stop() = server.close()

	private fun target(settings: Map<String, String> = emptyMap(), credential: String = secret) = ConnectionTarget(settings, ConnectorCredential(credential))
	private fun failure(block: () -> Unit): ConnectorFailure = catchThrowableOfType(ConnectorFailure::class.java) { block() }

	// ---- Claude Enterprise (evidence §1.2) ----

	private val claudeUser = """{"type":"user","id":"user_01AbCdEfGhIjKlMnOpQrSt","email":"jane@example.test","name":"Jane Smith","role":"user","added_at":"2026-06-12T09:14:03Z"}"""

	@Test
	fun `Claude — 구성원 두 페이지(after_id)와 대기 중 초대를 좌석으로 읽고 문서의 헤더를 보낸다`() {
		server.on("GET", "/v1/organizations/users",
			reply(200, """{"data":[$claudeUser],"has_more":true,"first_id":"user_01AbCdEfGhIjKlMnOpQrSt","last_id":"user_01AbCdEfGhIjKlMnOpQrSt","extra":{"unknown":1}}"""),
			reply(200, """{"data":[{"type":"user","id":"user_02","email":"Kim@Example.test","name":"Kim","role":"owner","added_at":"2026-01-02T03:04:05.123456Z","new_field":true}],"has_more":false,"first_id":"user_02","last_id":"user_02"}"""))
		server.on("GET", "/v1/organizations/invites", reply(200, """{"data":[
			{"type":"invite","id":"invite_01","email":"newhire@example.test","role":"managed","invited_at":"2026-07-06T16:20:11Z","expires_at":"2026-07-27T16:20:11Z","accepted_at":null,"status":"pending","rbac_group_ids":[]},
			{"type":"invite","id":"invite_02","email":"done@example.test","role":"user","invited_at":"2026-05-01T00:00:00Z","expires_at":"2026-05-22T00:00:00Z","accepted_at":"2026-05-02T00:00:00Z","status":"accepted"},
			{"type":"invite","id":"invite_03","email":"JANE@example.test","role":"user","invited_at":"2026-05-01T00:00:00Z","expires_at":"2026-05-22T00:00:00Z","accepted_at":null,"status":"pending"}
		],"has_more":false,"first_id":"invite_01","last_id":"invite_03"}"""))

		val seats = ClaudeEnterpriseConnector(http, server.base).listSeats(target())

		assertThat(seats).containsExactly(
			VendorSeat("jane@example.test", "user_01AbCdEfGhIjKlMnOpQrSt", "jane@example.test", assignedAt = Instant.parse("2026-06-12T09:14:03Z")),
			VendorSeat("Kim@Example.test", "user_02", "Kim@Example.test", assignedAt = Instant.parse("2026-01-02T03:04:05.123456Z")),
			// 대기 중 초대는 좌석을 잡는다 — 배정 대기. 이미 구성원인 이메일의 초대와 수락된 초대는 넣지 않는다.
			VendorSeat("newhire@example.test", null, "newhire@example.test", VendorSeatState.PENDING_ASSIGNMENT, assignedAt = Instant.parse("2026-07-06T16:20:11Z")),
		)
		val users = server.requests("/v1/organizations/users")
		assertThat(users.map { it.param("limit") to it.param("after_id") }).containsExactly("1000" to null, "1000" to "user_01AbCdEfGhIjKlMnOpQrSt")
		assertThat(server.received.map { it.header("x-api-key") to it.header("anthropic-version") }).containsOnly(secret to "2023-06-01")
	}

	@Test
	fun `Claude — 401 은 자격증명 무효, 403 은 권한 부족, 확인은 한 건만 읽는다`() {
		server.on("GET", "/v1/organizations/users", reply(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))
		assertThat(failure { ClaudeEnterpriseConnector(http, server.base).verify(target()) }.kind).isEqualTo(ConnectorFailure.Kind.INVALID_CREDENTIALS)
		assertThat(server.received.single().param("limit")).isEqualTo("1")
		server.on("GET", "/v1/organizations/users", reply(403, """{"type":"error","error":{"type":"permission_error","message":"missing read:members"}}"""))
		assertThat(failure { ClaudeEnterpriseConnector(http, server.base).listSeats(target()) }.kind).isEqualTo(ConnectorFailure.Kind.INSUFFICIENT_PERMISSION)
		assertThat(waits).describedAs("영구 실패는 다시 시도하지 않는다").isEmpty()
	}

	@Test
	fun `Claude — 429 는 Retry-After 만큼 기다렸다 다시 시도하고, 끝을 알리지 않는 페이지는 해석 불가다`() {
		server.on("GET", "/v1/organizations/users",
			reply(429, """{"type":"error","error":{"type":"rate_limit_error","message":"slow down"}}""", "retry-after" to "7"),
			reply(200, """{"data":[$claudeUser],"has_more":false,"first_id":"a","last_id":"a"}"""))
		server.on("GET", "/v1/organizations/invites", reply(200, """{"data":[],"has_more":false,"first_id":null,"last_id":null}"""))
		assertThat(ClaudeEnterpriseConnector(http, server.base).listSeats(target()).map { it.account }).containsExactly("jane@example.test")
		assertThat(waits).containsExactly(Duration.ofSeconds(7))

		server.on("GET", "/v1/organizations/users", reply(200, """{"data":[$claudeUser],"has_more":true,"first_id":"a","last_id":"a"}"""))
		assertThat(failure { ClaudeEnterpriseConnector(http, server.base).listSeats(target()) }.kind).isEqualTo(ConnectorFailure.Kind.INVALID_RESPONSE)
		server.on("GET", "/v1/organizations/users", reply(200, """{"data":[{"type":"user","id":"user_03"}],"has_more":false}"""))
		assertThat(failure { ClaudeEnterpriseConnector(http, server.base).listSeats(target()) }.kind).describedAs("이메일 없는 구성원").isEqualTo(ConnectorFailure.Kind.INVALID_RESPONSE)
	}

	@Test
	fun `일시 장애는 정한 횟수까지 다시 시도하고 넘으면 일시 장애로 끝난다 · 벤더의 대기 시간이 상한보다 길면 기다리지 않는다`() {
		server.on("GET", "/v1/organizations/users", reply(503, """{"type":"error","error":{"type":"overloaded_error"}}"""))
		assertThat(failure { ClaudeEnterpriseConnector(http, server.base).listSeats(target()) }.kind).isEqualTo(ConnectorFailure.Kind.UNAVAILABLE)
		assertThat(server.requests("/v1/organizations/users")).hasSize(3)
		assertThat(waits).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(2))

		waits.clear()
		server.on("GET", "/teams/members", reply(429, """{"error":"rate limited"}""", "Retry-After" to "60"))
		val limited = failure { CursorEnterpriseConnector(http, server.base).listSeats(target()) }
		assertThat(limited.kind to limited.retryAfter).isEqualTo(ConnectorFailure.Kind.RATE_LIMITED to Duration.ofSeconds(60))
		assertThat(waits).isEmpty()
		assertThat(limited.message).doesNotContain(secret)
	}

	// ---- Cursor Enterprise (evidence §3) ----

	@Test
	fun `Cursor — Basic 인증(키가 사용자 이름)으로 구성원을 읽고 제거된 구성원은 좌석이 아니다`() {
		server.on("GET", "/teams/members", reply(200, """{"teamMembers":[
			{"id":"user_PDSPmvukpYgZEDXsoNirw3CFhy","name":"Alex","email":"developer@example.test","role":"member","isRemoved":false},
			{"id":"user_kljUvI0ASZORvSEXf9hV0ydcso","name":"Sam","email":"admin@example.test","role":"owner","isRemoved":false,"futureField":{}},
			{"id":"user_gone","name":"Old","email":"old@example.test","role":"member","isRemoved":true}
		]}"""))
		val seats = CursorEnterpriseConnector(http, server.base).listSeats(target())
		assertThat(seats).containsExactly(
			VendorSeat("developer@example.test", "user_PDSPmvukpYgZEDXsoNirw3CFhy", "developer@example.test"),
			VendorSeat("admin@example.test", "user_kljUvI0ASZORvSEXf9hV0ydcso", "admin@example.test"),
		)
		assertThat(seats.map { it.tier to it.lastActivityAt }).describedAs("좌석 유형·활동은 응답에 없다 — 추정하지 않는다").containsOnly(null to null)
		assertThat(server.received.single().header("Authorization")).isEqualTo("Basic " + Base64.getEncoder().encodeToString("$secret:".toByteArray()))

		server.on("GET", "/teams/members", reply(200, """{"teamMembers":[]}"""))
		assertThat(CursorEnterpriseConnector(http, server.base).listSeats(target())).isEmpty()
		server.on("GET", "/teams/members", reply(200, """{"members":[]}"""))
		assertThat(failure { CursorEnterpriseConnector(http, server.base).listSeats(target()) }.kind).isEqualTo(ConnectorFailure.Kind.INVALID_RESPONSE)
	}

	@Test
	fun `구현한 커넥터는 설명과 맞아 조립되고, 설명의 기능은 벤더 문서 근거 안이다`() {
		val connectors = SeatConnectors(listOf(ClaudeEnterpriseConnector(http), CursorEnterpriseConnector(http)))
		// ADR 0049·0054: 해제는 둘 다, 복원을 구현한 커넥터는 없다(Claude 재초대는 역할을 정해야 하고 Cursor 는 API 가 없다).
		// ADR 0050: 청구는 문서가 비용·지출 API 를 준 Claude Enterprise·Cursor Enterprise 만.
		assertThat(ConnectorDescriptors.ALL.associate { it.id to connectors.byId(it.id)?.implemented() }).isEqualTo(mapOf(
			"claude_enterprise" to setOf(Capability.SEAT_LIST, Capability.SEAT_RELEASE, Capability.BILLING),
			"cursor_enterprise" to setOf(Capability.SEAT_LIST, Capability.SEAT_RELEASE, Capability.BILLING),
		))
		assertThat(ConnectorDescriptors.CLAUDE_ENTERPRISE.accountRefRequired).containsExactly(Capability.SEAT_RELEASE)
	}

	// ---- 해제·복원 (ADR 0049 — 요청 성공은 완료가 아니다) ----

	@Test
	fun `Claude — 제거는 구성원 ID 로 DELETE 하고 user_deleted 면 끝났다, 구성원 ID 가 없거나 SCIM 조직의 400 은 벤더 거절이다`() {
		server.on("DELETE", "/v1/organizations/users/user_01AbCdEfGhIjKlMnOpQrSt", reply(200, """{"type":"user_deleted","id":"user_01AbCdEfGhIjKlMnOpQrSt"}"""))
		val claude = ClaudeEnterpriseConnector(http, server.base)
		assertThat(claude.release.release(target(), VendorAccount("jane@example.test", "user_01AbCdEfGhIjKlMnOpQrSt"))).isEqualTo(ControlResult(ControlStatus.COMPLETED))
		with(server.received.single()) {
			assertThat(listOf(method, header("x-api-key"), header("anthropic-version"), body)).containsExactly("DELETE", secret, "2023-06-01", "")
		}
		val before = server.received.size
		assertThat(failure { claude.release.release(target(), VendorAccount("jane@example.test", null)) }.kind).isEqualTo(ConnectorFailure.Kind.VENDOR_REJECTED)
		assertThat(server.received).describedAs("구성원 ID 없이 벤더를 부르지 않는다").hasSize(before)
		server.on("DELETE", "/v1/organizations/users/user_scim", reply(400, """{"type":"error","error":{"type":"invalid_request_error","message":"managed by SCIM"}}"""))
		assertThat(failure { claude.release.release(target(), VendorAccount("scim@example.test", "user_scim")) }.kind).isEqualTo(ConnectorFailure.Kind.VENDOR_REJECTED)
		server.on("DELETE", "/v1/organizations/users/user_odd", reply(200, """{"type":"something_else"}"""))
		assertThat(failure { claude.release.release(target(), VendorAccount("odd@example.test", "user_odd")) }.kind).isEqualTo(ConnectorFailure.Kind.INVALID_RESPONSE)
		assertThat(claude.restore).describedAs("재초대는 구현하지 않는다 — 관리자 조치").isNull()
	}

	@Test
	fun `Cursor — 제거는 userId 가 있으면 그것만, 없으면 email 만 보내고 success 가 false 면 벤더 거절이다`() {
		server.on("POST", "/teams/remove-member",
			reply(200, """{"success":true,"userId":"user_PDSPmvukpYgZEDXsoNirw3CFhy","hasBillingCycleUsage":true}"""),
			reply(200, """{"success":true,"userId":"user_2","hasBillingCycleUsage":false}"""),
			reply(200, """{"success":false}"""))
		val cursor = CursorEnterpriseConnector(http, server.base)
		assertThat(cursor.release.release(target(), VendorAccount("developer@example.test", "user_PDSPmvukpYgZEDXsoNirw3CFhy")).status).isEqualTo(ControlStatus.COMPLETED)
		assertThat(cursor.release.release(target(), VendorAccount("other@example.test", null)).status).isEqualTo(ControlStatus.COMPLETED)
		assertThat(failure { cursor.release.release(target(), VendorAccount("last@example.test", null)) }.kind).isEqualTo(ConnectorFailure.Kind.VENDOR_REJECTED)
		val mapper = JsonMapper.builder().build()
		assertThat(server.requests("/teams/remove-member").map { mapper.readTree(it.body) }.map { it.propertyNames().toList() to it.path("userId").asString(it.path("email").asString()) })
			.containsExactly(listOf("userId") to "user_PDSPmvukpYgZEDXsoNirw3CFhy", listOf("email") to "other@example.test", listOf("email") to "last@example.test")
		assertThat(server.received.map { it.header("Content-Type") }).containsOnly("application/json")
		assertThat(cursor.restore).isNull()
	}

	// ---- 청구 누계 (ADR 0050 — 좌석 구독료가 아니다) ----

	@Test
	fun `Claude — 비용 보고서를 이번 달 시작부터 한 시간 칸으로 끝까지 읽고 센트 금액을 달러로 더한다, USD 가 아니면 해석 불가다`() {
		val start = Instant.parse("2026-09-30T15:00:00Z")
		val now = Instant.parse("2026-10-01T03:20:00Z")
		val report = "/v1/organizations/analytics/cost_report"
		server.on("GET", report,
			reply(200, """{"data":[{"starting_at":"2026-09-30T15:00:00Z","ending_at":"2026-09-30T16:00:00Z","results":[
				{"amount":"41280.000000","cost_type":"tokens","currency":"USD","list_amount":"50000","model":"claude-opus-5","product":"chat","requests":3},
				{"amount":"12.5","cost_type":"code_execution","currency":"USD","list_amount":"12.5","model":null,"product":"claude_code","requests":1}]}],
				"data_refreshed_at":"2026-10-01T03:00:00Z","has_more":true,"next_page":"p2","organization_id":"org_1"}"""),
			reply(200, """{"data":[{"starting_at":"2026-10-01T00:00:00Z","ending_at":"2026-10-01T01:00:00Z","results":[{"amount":"100","currency":"USD"}]},
				{"starting_at":"2026-10-01T01:00:00Z","ending_at":"2026-10-01T02:00:00Z","results":[]}],"has_more":false,"next_page":null}"""))
		val billed = ClaudeEnterpriseConnector(http, server.base).billing.currentPeriod(target(), now, start)
		// (41280 + 12.5 + 100) 센트 = $413.925 — 할인 뒤·크레딧 전 사용 비용, 30일까지 고쳐질 수 있다.
		assertThat(billed.amount).isEqualByComparingTo("413.925")
		assertThat(billed.copy(amount = java.math.BigDecimal.ZERO)).isEqualTo(BilledAmount(start, now, java.math.BigDecimal.ZERO, "USD", BilledKind.USAGE_COST, finalized = false))
		val requests = server.requests(report)
		assertThat(requests.map { Triple(it.param("starting_at"), it.param("bucket_width"), it.param("page")) })
			.containsExactly(Triple("2026-09-30T15:00:00Z", "1h", null), Triple("2026-09-30T15:00:00Z", "1h", "p2"))
		assertThat(requests.map { it.param("ending_at") }).containsOnly("2026-10-01T03:20:00Z")
		server.on("GET", report, reply(200, """{"data":[{"results":[{"amount":"1","currency":"EUR"}]}],"has_more":false}"""))
		assertThat(failure { ClaudeEnterpriseConnector(http, server.base).billing.currentPeriod(target(), now, start) }.kind).isEqualTo(ConnectorFailure.Kind.INVALID_RESPONSE)
		server.on("GET", report, reply(403, """{"type":"error","error":{"type":"permission_error","message":"missing read:analytics"}}"""))
		assertThat(failure { ClaudeEnterpriseConnector(http, server.base).billing.currentPeriod(target(), now, start) }.kind).isEqualTo(ConnectorFailure.Kind.INSUFFICIENT_PERMISSION)
	}

	@Test
	fun `Cursor — 이번 청구 주기의 on-demand 지출(spendCents)만 모든 페이지에서 더하고 기간은 벤더의 주기 시작부터다`() {
		val now = Instant.parse("2026-10-01T03:20:00Z")
		server.on("POST", "/teams/spend",
			reply(200, """{"teamMemberSpend":[{"userId":"user_1","spendCents":2450.125487,"overallSpendCents":9000.5,"fastPremiumRequests":1250,"name":"Alex","email":"dev@example.test","role":"member"}],
				"subscriptionCycleStart":1758931200000,"totalMembers":2,"totalPages":2}"""),
			reply(200, """{"teamMemberSpend":[{"userId":"user_2","spendCents":0,"overallSpendCents":1200,"email":"kim@example.test","role":"member"}],
				"subscriptionCycleStart":1758931200000,"totalMembers":2,"totalPages":2}"""))
		val billed = CursorEnterpriseConnector(http, server.base).billing.currentPeriod(target(), now, Instant.parse("2026-09-30T15:00:00Z"))
		// 구독에 든 사용분(overallSpendCents)은 더하지 않는다 — 2450.125487 센트.
		assertThat(billed.amount).isEqualByComparingTo("24.50125487")
		assertThat(billed.copy(amount = java.math.BigDecimal.ZERO))
			.isEqualTo(BilledAmount(Instant.ofEpochMilli(1758931200000), now, java.math.BigDecimal.ZERO, "USD", BilledKind.USAGE_SPEND, finalized = false))
		val mapper = JsonMapper.builder().build()
		assertThat(server.requests("/teams/spend").map { mapper.readTree(it.body).let { b -> b.path("page").asInt() to b.path("pageSize").asInt() } }).containsExactly(1 to 100, 2 to 100)
		assertThat(server.requests("/teams/spend").map { it.header("Authorization") }).containsOnly("Basic " + Base64.getEncoder().encodeToString("$secret:".toByteArray()))
		server.on("POST", "/teams/spend", reply(200, """{"teamMemberSpend":[{"userId":"user_1","overallSpendCents":1}],"subscriptionCycleStart":1758931200000,"totalPages":1}"""))
		assertThat(failure { CursorEnterpriseConnector(http, server.base).billing.currentPeriod(target(), now, now) }.kind).isEqualTo(ConnectorFailure.Kind.INVALID_RESPONSE)
	}
}
