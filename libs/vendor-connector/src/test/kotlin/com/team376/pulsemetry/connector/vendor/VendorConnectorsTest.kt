package com.team376.pulsemetry.connector.vendor

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 커넥터 넷을 모의 벤더 서버로 검증한다. 모의 응답은 `docs/vendor-connector-evidence.md` 의 요청·응답 예시를 그대로 재현한다(이메일만 `@example.test`로).
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

	// ---- GitHub Copilot (evidence §4) ----

	private val octocat = """{"assignee":{"login":"octocat","id":1,"type":"User"},"assigning_team":{"id":1,"name":"Justice League","slug":"justice-league"},
		"pending_cancellation_date":null,"last_activity_at":"2024-10-01T19:32:20Z","last_activity_editor":"Visual Studio Code",
		"last_authenticated_at":"2024-10-01T19:32:20Z","created_at":"2024-10-01T19:32:20Z","plan_type":"business"}"""

	@Test
	fun `Copilot — link 헤더의 다음 페이지까지 읽고 해제 예정·로그인 계정·null 담당자를 문서대로 다룬다`() {
		server.on("GET", "/orgs/octo-org/copilot/billing/seats",
			reply(200, """{"total_seats":3,"seats":[$octocat]}""", "link" to "<${server.base}/orgs/octo-org/copilot/billing/seats?per_page=100&page=2>; rel=\"next\", <x>; rel=\"last\""),
			reply(200, """{"total_seats":3,"seats":[
				{"assignee":{"login":"Hubot","id":2,"type":"User"},"pending_cancellation_date":"2025-02-15","last_activity_at":null,"created_at":"2024-11-01T00:00:00Z","plan_type":"business"},
				{"assignee":null,"pending_cancellation_date":null,"last_activity_at":null,"created_at":"2024-11-02T00:00:00Z","plan_type":"business"}
			]}""", "link" to "<x>; rel=\"prev\", <y>; rel=\"first\""))

		val seats = CopilotConnector(http, server.base).listSeats(target(mapOf("organization" to "octo-org")))

		assertThat(seats).containsExactly(
			VendorSeat("octocat", assignedAt = Instant.parse("2024-10-01T19:32:20Z"), lastActivityAt = Instant.parse("2024-10-01T19:32:20Z")),
			// 요청 성공은 즉시 회수가 아니다 — 벤더가 보고한 예정일을 그대로 둔다. 활동이 없다는 것은 모름이다.
			VendorSeat("Hubot", state = VendorSeatState.PENDING_RELEASE, releaseEffectiveOn = LocalDate.parse("2025-02-15"), assignedAt = Instant.parse("2024-11-01T00:00:00Z")),
		)
		assertThat(seats.map { it.email }).describedAs("Copilot 좌석에는 이메일이 없다").containsOnly(null)
		val requests = server.requests("/orgs/octo-org/copilot/billing/seats")
		assertThat(requests.map { it.param("page") to it.param("per_page") }).containsExactly("1" to "100", "2" to "100")
		assertThat(requests.map { Triple(it.header("Accept"), it.header("Authorization"), it.header("X-GitHub-Api-Version")) })
			.containsOnly(Triple("application/vnd.github+json", "Bearer $secret", "2022-11-28"))
	}

	@Test
	fun `Copilot — 한도 신호가 있는 403 은 한도 초과, 없는 조직(404)은 벤더 거절, 조직 이름은 경로에서 인코딩한다`() {
		server.on("GET", "/orgs/octo-org/copilot/billing/seats",
			reply(403, """{"message":"API rate limit exceeded"}""", "x-ratelimit-remaining" to "0", "x-ratelimit-reset" to (Instant.parse("2026-10-01T00:00:05Z").epochSecond).toString()),
			reply(200, """{"total_seats":0,"seats":[]}"""))
		assertThat(CopilotConnector(http, server.base).listSeats(target(mapOf("organization" to "octo-org")))).isEmpty()
		assertThat(waits).containsExactly(Duration.ofSeconds(5))

		server.on("GET", "/orgs/octo-org/copilot/billing/seats", reply(403, """{"message":"Must have admin rights to Repository."}"""))
		assertThat(failure { CopilotConnector(http, server.base).verify(target(mapOf("organization" to "octo-org"))) }.kind).isEqualTo(ConnectorFailure.Kind.INSUFFICIENT_PERMISSION)
		assertThat(server.requests("/orgs/octo-org/copilot/billing/seats").last().param("per_page")).isEqualTo("1")
		assertThat(failure { CopilotConnector(http, server.base).verify(target(mapOf("organization" to "other org/x"))) }.kind).isEqualTo(ConnectorFailure.Kind.VENDOR_REJECTED)
		assertThat(server.received.last().path).isEqualTo("/orgs/other%20org%2Fx/copilot/billing/seats")
	}

	// ---- Gemini Code Assist (evidence §5, Google 서비스 계정 OAuth) ----

	private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
	private val serviceAccount = JsonMapper.builder().build().writeValueAsString(mapOf(
		"type" to "service_account", "project_id" to "demo-project", "private_key_id" to "kid-1",
		"private_key" to "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keys.private.encoded) + "\n-----END PRIVATE KEY-----\n",
		"client_email" to "seat-sync@demo-project.example.test", "client_id" to "1", "token_uri" to "https://oauth2.googleapis.com/token"))

	/** 모의 토큰 엔드포인트 — 문서의 형식대로 서명한 JWT 인지 공개키로 확인하고 토큰을 준다. */
	private fun tokenEndpoint(): (MockVendorServer.Received) -> MockVendorServer.Reply = { request ->
		val form = request.body.split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
		val (header, claims, signature) = form.getValue("assertion").split('.')
		val valid = Signature.getInstance("SHA256withRSA").apply { initVerify(keys.public as RSAPublicKey); update("$header.$claims".toByteArray()) }
			.verify(Base64.getUrlDecoder().decode(signature))
		val mapper = JsonMapper.builder().build()
		val h = mapper.readTree(Base64.getUrlDecoder().decode(header))
		val c = mapper.readTree(Base64.getUrlDecoder().decode(claims))
		val ok = valid && form["grant_type"] == "urn:ietf:params:oauth:grant-type:jwt-bearer" && h.path("alg").asString() == "RS256" && h.path("kid").asString() == "kid-1" &&
			c.path("iss").asString() == "seat-sync@demo-project.example.test" && c.path("aud").asString() == "https://oauth2.googleapis.com/token" &&
			c.path("scope").asString() == "https://www.googleapis.com/auth/cloud-platform" && c.path("iat").asLong() == clock.instant().epochSecond &&
			c.path("exp").asLong() - c.path("iat").asLong() == 3600L
		if (ok) MockVendorServer.Reply(200, """{"access_token":"ya29.fake-access","scope":"https://www.googleapis.com/auth/cloud-platform","token_type":"Bearer","expires_in":3600}""")
		else MockVendorServer.Reply(400, """{"error":"invalid_grant"}""")
	}

	private val pool = "/v1/billingAccounts/0123-ABCD/orders/order-9/licensePool:enumerateLicensedUsers"
	private fun gemini() = GeminiConnector(http, server.base, server.base.resolve("/token"), clock)
	private val geminiSettings = mapOf("billingAccount" to "0123-ABCD", "order" to "order-9", "project" to "demo-project")

	@Test
	fun `Gemini — 서비스 계정 키로 서명한 JWT 를 토큰으로 바꾸고 nextPageToken 이 없을 때까지 라이선스 사용자를 읽는다`() {
		server.on("POST", "/token", tokenEndpoint())
		server.on("GET", pool,
			reply(200, """{"licensedUsers":[{"username":"dana@example.test","assignTime":"2024-09-26T16:24:40.559222Z"}],"nextPageToken":"p2"}"""),
			reply(200, """{"licensedUsers":[{"username":"eli@example.test","assignTime":"2024-09-27T00:00:00Z","recentUsageTime":"2026-09-30T10:00:00Z","unknown":"x"}]}"""))

		val seats = gemini().listSeats(target(geminiSettings, serviceAccount))

		assertThat(seats).containsExactly(
			VendorSeat("dana@example.test", email = "dana@example.test", assignedAt = Instant.parse("2024-09-26T16:24:40.559222Z")),
			VendorSeat("eli@example.test", email = "eli@example.test", assignedAt = Instant.parse("2024-09-27T00:00:00Z"), lastActivityAt = Instant.parse("2026-09-30T10:00:00Z")),
		)
		val listed = server.requests(pool)
		assertThat(listed.map { it.param("pageToken") }).containsExactly(null, "p2")
		assertThat(listed.map { it.header("Authorization") to it.header("X-Goog-User-Project") }).containsOnly("Bearer ya29.fake-access" to "demo-project")
		assertThat(server.requests("/token").single().header("Content-Type")).isEqualTo("application/x-www-form-urlencoded")
	}

	@Test
	fun `Gemini — 빈 풀은 licensedUsers 가 없어도 빈 목록이고, 키가 서비스 계정 키가 아니거나 토큰 엔드포인트가 거절하면 자격증명 무효다`() {
		server.on("POST", "/token", tokenEndpoint())
		server.on("GET", pool, reply(200, "{}"))
		assertThat(gemini().listSeats(target(geminiSettings, serviceAccount))).isEmpty()
		gemini().verify(target(geminiSettings, serviceAccount))
		assertThat(server.requests(pool).last().param("pageSize")).isEqualTo("1")

		val before = server.received.size
		assertThat(failure { gemini().verify(target(geminiSettings, "not-json")) }.kind).isEqualTo(ConnectorFailure.Kind.INVALID_CREDENTIALS)
		assertThat(server.received).describedAs("형식이 틀린 키로 벤더를 부르지 않는다").hasSize(before)
		val otherKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
		val forged = serviceAccount.replace(Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keys.private.encoded).replace("\n", "\\n"),
			Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(otherKey.private.encoded).replace("\n", "\\n"))
		assertThat(forged).isNotEqualTo(serviceAccount)
		assertThat(failure { gemini().listSeats(target(geminiSettings, forged)) }.kind).isEqualTo(ConnectorFailure.Kind.INVALID_CREDENTIALS)
	}

	@Test
	fun `구현한 커넥터는 설명과 맞아 조립되고, 설명의 기능은 벤더 문서 근거 안이다`() {
		val connectors = SeatConnectors(listOf(ClaudeEnterpriseConnector(http), CursorEnterpriseConnector(http), CopilotConnector(http), GeminiConnector(http)))
		assertThat(ConnectorDescriptors.ALL.map { connectors.byId(it.id)?.implemented() }).containsOnly(setOf(Capability.SEAT_LIST))
	}
}
