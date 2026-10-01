package com.team376.pulsemetry.connector.vendor

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * 실계정 검증 도구(`verifyVendorAccount`)를 모의 서버에 붙여 본다 — 읽기만 하고, 입력이 없으면 건너뛰지 않고 실패하며, 비밀·이메일·로그인을 찍지 않는다.
 */
class VendorAccountCheckTest {
	private val server = MockVendorServer()
	private val output = ByteArrayOutputStream()
	private val secret = "fake-vendor-credential-" + "v".repeat(20)
	private val http = VendorHttp(HttpPolicy(Duration.ofSeconds(5), 1, Duration.ZERO, Duration.ZERO))

	@AfterEach fun stop() = server.close()

	private fun check(vendor: String, env: Map<String, String>): Int = VendorAccountCheck(env, PrintStream(output, true), http).run(vendor)
	private val printed get() = output.toString()

	@Test
	fun `Copilot — 연결 확인과 좌석 목록을 읽고 수만 찍는다`() {
		server.on("GET", "/orgs/octo-org/copilot/billing/seats", reply(200, """{"total_seats":2,"seats":[
			{"assignee":{"login":"octocat","id":1,"type":"User"},"pending_cancellation_date":null,"last_activity_at":"2024-10-01T19:32:20Z","created_at":"2024-10-01T19:32:20Z","plan_type":"business"},
			{"assignee":{"login":"hubot","id":2,"type":"User"},"pending_cancellation_date":"2025-02-15","last_activity_at":null,"created_at":"2024-11-01T00:00:00Z","plan_type":"business"}]}"""))
		val code = check("copilot", mapOf("PULSEMETRY_VERIFY_CREDENTIAL" to secret, "PULSEMETRY_VERIFY_SETTING_ORGANIZATION" to "octo-org", "PULSEMETRY_VERIFY_BASE_URL" to server.base.toString()))
		assertThat(code).withFailMessage(printed).isZero()
		assertThat(printed).contains("[copilot] 연결 확인: 통과", "좌석 2개 — assigned 1, pending_assignment 0, pending_release 1", "마지막 활동 있음 1")
			.doesNotContain(secret, "octocat", "hubot")
		assertThat(server.received.map { it.method }).describedAs("읽기만 한다").containsOnly("GET")
	}

	@Test
	fun `Cursor — 청구를 구현한 커넥터는 이번 주기 청구 누계도 찍는다(구성원 이메일은 찍지 않는다)`() {
		server.on("GET", "/teams/members", reply(200, """{"teamMembers":[{"id":"user_1","email":"dev@example.test","isRemoved":false}]}"""))
		server.on("POST", "/teams/spend", reply(200, """{"teamMemberSpend":[{"userId":"user_1","spendCents":1234.5,"email":"dev@example.test"}],"subscriptionCycleStart":1758931200000,"totalPages":1}"""))
		val clock = java.time.Clock.fixed(java.time.Instant.parse("2026-10-01T00:00:00Z"), java.time.ZoneOffset.UTC)
		val code = VendorAccountCheck(mapOf("PULSEMETRY_VERIFY_CREDENTIAL" to secret, "PULSEMETRY_VERIFY_BASE_URL" to server.base.toString()), PrintStream(output, true), http, clock)
			.run("cursor_enterprise")
		assertThat(code).withFailMessage(printed).isZero()
		assertThat(printed).contains("[cursor_enterprise] 청구 누계 12.345 USD — usage_spend, 2025-09-27T00:00:00Z ~ 2026-10-01T00:00:00Z, 확정 false")
			.doesNotContain(secret, "dev@example.test")
	}

	@Test
	fun `입력이 없으면 건너뛰지 않고 2, 벤더가 거절하면 1 이다`() {
		assertThat(check("copilot", emptyMap())).isEqualTo(2)
		assertThat(check("copilot", mapOf("PULSEMETRY_VERIFY_CREDENTIAL" to secret))).isEqualTo(2)
		assertThat(printed).contains("PULSEMETRY_VERIFY_SETTING_ORGANIZATION")
		assertThat(check("nope", mapOf("PULSEMETRY_VERIFY_CREDENTIAL" to secret))).isEqualTo(2)
		server.on("GET", "/teams/members", reply(401, """{"error":"unauthorized"}"""))
		assertThat(check("cursor_enterprise", mapOf("PULSEMETRY_VERIFY_CREDENTIAL" to secret, "PULSEMETRY_VERIFY_BASE_URL" to server.base.toString()))).isEqualTo(1)
		assertThat(printed).contains("[cursor_enterprise] 실패: invalid_credentials").doesNotContain(secret)
	}

	@Test
	fun `자격증명은 파일로도 받는다 — 서비스 계정 키 JSON 이 아니면 자격증명 무효로 끝난다`(@TempDir dir: Path) {
		val file = Files.writeString(dir.resolve("key.json"), "not-a-service-account-key")
		val code = check("gemini", mapOf("PULSEMETRY_VERIFY_CREDENTIAL_FILE" to file.toString(), "PULSEMETRY_VERIFY_SETTING_BILLINGACCOUNT" to "b",
			"PULSEMETRY_VERIFY_SETTING_ORDER" to "o", "PULSEMETRY_VERIFY_SETTING_PROJECT" to "p", "PULSEMETRY_VERIFY_BASE_URL" to server.base.toString()))
		assertThat(code).isEqualTo(1)
		assertThat(printed).contains("[gemini] 실패: invalid_credentials")
		assertThat(server.received).isEmpty()
	}
}
