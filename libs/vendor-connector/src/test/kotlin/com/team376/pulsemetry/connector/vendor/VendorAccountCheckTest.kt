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
	fun `Claude — 연결 확인과 좌석 목록을 읽고 수만 찍는다`() {
		server.on("GET", "/v1/organizations/users", reply(200, """{"data":[
			{"type":"user","id":"user_1","email":"jane@example.test","name":"Jane","role":"user","added_at":"2026-06-12T09:14:03Z"},
			{"type":"user","id":"user_2","email":"kim@example.test","name":"Kim","role":"owner","added_at":"2026-01-02T03:04:05Z"}],"has_more":false,"first_id":"user_1","last_id":"user_2"}"""))
		server.on("GET", "/v1/organizations/invites", reply(200, """{"data":[
			{"type":"invite","id":"invite_1","email":"newhire@example.test","role":"user","invited_at":"2026-07-06T16:20:11Z","expires_at":"2026-07-27T16:20:11Z","accepted_at":null,"status":"pending"}],
			"has_more":false,"first_id":"invite_1","last_id":"invite_1"}"""))
		server.on("GET", "/v1/organizations/analytics/cost_report", reply(200, """{"data":[],"has_more":false,"next_page":null}"""))
		val code = check("claude_enterprise", mapOf("PULSEMETRY_VERIFY_CREDENTIAL" to secret, "PULSEMETRY_VERIFY_BASE_URL" to server.base.toString()))
		assertThat(code).withFailMessage(printed).isZero()
		assertThat(printed).contains("[claude_enterprise] 연결 확인: 통과", "좌석 3개 — assigned 2, pending_assignment 1, pending_release 0", "이메일 있음 3")
			.doesNotContain(secret, "jane@example.test", "kim@example.test", "newhire@example.test")
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
		assertThat(check("cursor_enterprise", emptyMap())).isEqualTo(2)
		assertThat(printed).contains("PULSEMETRY_VERIFY_CREDENTIAL")
		assertThat(check("nope", mapOf("PULSEMETRY_VERIFY_CREDENTIAL" to secret))).isEqualTo(2)
		// 연동 대상이 아닌 제품(ADR 0054)은 커넥터 ID 가 아니다.
		for (vendor in listOf("copilot", "gemini")) assertThat(check(vendor, mapOf("PULSEMETRY_VERIFY_CREDENTIAL" to secret))).describedAs(vendor).isEqualTo(2)
		assertThat(printed).contains("커넥터 ID 는 claude_enterprise, cursor_enterprise 중 하나다")
		assertThat(server.received).isEmpty()
		server.on("GET", "/teams/members", reply(401, """{"error":"unauthorized"}"""))
		assertThat(check("cursor_enterprise", mapOf("PULSEMETRY_VERIFY_CREDENTIAL" to secret, "PULSEMETRY_VERIFY_BASE_URL" to server.base.toString()))).isEqualTo(1)
		assertThat(printed).contains("[cursor_enterprise] 실패: invalid_credentials").doesNotContain(secret)
	}

	@Test
	fun `자격증명은 파일로도 받는다 — 앞뒤 공백을 떼고 그 값으로 부른다`(@TempDir dir: Path) {
		val file = Files.writeString(dir.resolve("key.txt"), "  $secret\n")
		server.on("GET", "/teams/members", reply(401, """{"error":"unauthorized"}"""))
		val code = check("cursor_enterprise", mapOf("PULSEMETRY_VERIFY_CREDENTIAL_FILE" to file.toString(), "PULSEMETRY_VERIFY_BASE_URL" to server.base.toString()))
		assertThat(code).isEqualTo(1)
		assertThat(printed).contains("[cursor_enterprise] 실패: invalid_credentials").doesNotContain(secret)
		assertThat(server.received.single().header("Authorization")).isEqualTo("Basic " + java.util.Base64.getEncoder().encodeToString("$secret:".toByteArray()))
	}
}
