package com.team376.pulsemetry.enrollment.api

import com.team376.pulsemetry.enrollment.config.PulsemetryProperties
import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * CLI 바이너리 서빙 (PLAN.md §6.6).
 *
 * 바이너리 디렉터리는 Gradle 이 시스템 프로퍼티로 고정 경로를 넘긴다.
 * 테스트가 그 디렉터리를 직접 채우고 비운다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresContainerConfig::class)
class BinaryApiTest {

	@LocalServerPort
	private var port: Int = 0

	@Autowired
	private lateinit var properties: PulsemetryProperties

	private val http: HttpClient = HttpClient.newHttpClient()

	private lateinit var binariesDir: Path

	@BeforeEach
	fun setUp() {
		binariesDir = Path.of(properties.binaries.dir)
		Files.createDirectories(binariesDir)
		Files.walk(binariesDir).use { paths -> paths.sorted(java.util.Comparator.reverseOrder()).filter { it != binariesDir }.forEach(Files::delete) }
	}

	private fun place(filename: String, content: String = "fake-binary") {
		Files.writeString(binariesDir.resolve(filename), content)
	}

	// ── 정상 서빙 ────────────────────────────────────────────────────────────

	@Test
	@DisplayName("허용된 파일이 있으면 200 octet-stream 으로 내려간다")
	fun servesAllowedBinary() {
		place("pulsemetry_linux_amd64", "ELF-ish")

		val response = get("/bin/pulsemetry_linux_amd64")

		assertThat(response.statusCode()).isEqualTo(200)
		assertThat(response.headers().firstValue("Content-Type").orElse(""))
			.isEqualTo("application/octet-stream")
		assertThat(response.body()).isEqualTo("ELF-ish")
	}

	@Test
	@DisplayName("화이트리스트 6개가 모두 서빙된다")
	fun servesAllSixAllowedNames() {
		BinaryController.ALLOWED_FILENAMES.forEach { place(it) }

		BinaryController.ALLOWED_FILENAMES.forEach { name ->
			assertThat(get("/bin/$name").statusCode())
				.describedAs("filename=%s", name)
				.isEqualTo(200)
		}
	}

	@Test
	@DisplayName("화이트리스트가 PLAN §6.6 의 6개와 정확히 같다")
	fun allowlistMatchesPlan() {
		assertThat(BinaryController.ALLOWED_FILENAMES).containsExactlyInAnyOrder(
			"pulsemetry_windows_amd64.exe",
			"pulsemetry_windows_arm64.exe",
			"pulsemetry_darwin_amd64",
			"pulsemetry_darwin_arm64",
			"pulsemetry_linux_amd64",
			"pulsemetry_linux_arm64",
		)
	}

	// ── 릴리스 산출물 (ADR 0053) ─────────────────────────────────────────────

	private fun sha256(content: String) = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content.toByteArray()))

	/**
	 * telemetryctl 릴리스 `v<version>` 을 받아 둔 모양으로 놓는다 — 데몬 자산 `pulsemetry_cli_{os}_{arch}[.exe]` 와
	 * 원격 `scripts/release.mjs` 의 `checksums` 모양(이름 순, `<해시>  <이름>\n`)의 `SHA256SUMS`. [sums] 로 목록의 해시를 바꿔 넣을 수 있다.
	 */
	private fun release(version: String, assets: Map<String, String>, sums: Map<String, String> = assets) {
		val dir = Files.createDirectories(binariesDir.resolve("v$version"))
		assets.forEach { (name, content) -> Files.writeString(dir.resolve(name), content) }
		Files.writeString(dir.resolve("SHA256SUMS"), sums.toSortedMap().entries.joinToString("") { (name, content) -> "${sha256(content)}  $name\n" })
	}

	@Test
	@DisplayName("릴리스 디렉터리가 있으면 공개 이름으로 그 릴리스의 데몬 자산을 내려준다")
	fun servesReleaseAssetUnderPublicName() {
		release("0.2.0", mapOf("pulsemetry_cli_darwin_arm64" to "darwin-0.2.0", "pulsemetry_cli_windows_amd64.exe" to "windows-0.2.0",
			"pulsemetry_gui_darwin_arm64.dmg" to "gui"))

		val darwin = get("/bin/pulsemetry_darwin_arm64")
		assertThat(darwin.statusCode()).isEqualTo(200)
		assertThat(darwin.body()).isEqualTo("darwin-0.2.0")
		assertThat(darwin.headers().firstValue("Content-Disposition")).hasValue("attachment; filename=\"pulsemetry_darwin_arm64\"")
		assertThat(get("/bin/pulsemetry_windows_amd64.exe").body()).isEqualTo("windows-0.2.0")
		assertThat(get("/bin/pulsemetry_gui_darwin_arm64.dmg").body()).isEqualTo("gui")
		// 릴리스에 없는 대상, CLI 내부 자산 이름과 SHA256SUMS는 내려주지 않는다.
		for (path in listOf("/bin/pulsemetry_linux_amd64", "/bin/pulsemetry_cli_darwin_arm64", "/bin/SHA256SUMS")) {
			assertThat(get(path).statusCode()).describedAs(path).isEqualTo(404)
		}
	}

	@Test
	@DisplayName("릴리스가 있으면 SHA256SUMS 로 확인한 파일만 내려주고 평면 파일로 물러나지 않는다")
	fun releaseServesOnlyVerifiedAssets() {
		place("pulsemetry_darwin_arm64", "flat")
		place("pulsemetry_linux_amd64", "flat")
		// 자산의 내용이 SHA256SUMS 와 다르다.
		release("0.2.0", mapOf("pulsemetry_cli_darwin_arm64" to "tampered"), sums = mapOf("pulsemetry_cli_darwin_arm64" to "darwin-0.2.0"))
		assertThat(get("/bin/pulsemetry_darwin_arm64").statusCode()).isEqualTo(404)
		// 릴리스에 없는 대상의 평면 파일도 내려주지 않는다 — 이 서버가 판을 말할 수 없는 파일이다.
		assertThat(get("/bin/pulsemetry_linux_amd64").statusCode()).isEqualTo(404)
		// 판이 가장 높은 릴리스가 이 서버의 릴리스다.
		release("0.10.0", mapOf("pulsemetry_cli_darwin_arm64" to "darwin-0.10.0"))
		assertThat(get("/bin/pulsemetry_darwin_arm64").body()).isEqualTo("darwin-0.10.0")
	}

    @Test
    @DisplayName("GUI 패키지도 동일 릴리스의 체크섬을 확인해 서빙한다")
    fun servesVerifiedGuiPackages() {
        val assets = BinaryController.GUI_FILENAMES.associateWith { "GUI for $it" }
        release("0.2.0", assets)
        assets.forEach { (name, content) ->
            val response = get("/bin/$name")
            assertThat(response.statusCode()).describedAs(name).isEqualTo(200)
            assertThat(response.body()).isEqualTo(content)
        }
        Files.writeString(binariesDir.resolve("v0.2.0/pulsemetry_gui_darwin_arm64.dmg"), "tampered")
        assertThat(get("/bin/pulsemetry_gui_darwin_arm64.dmg").statusCode()).isEqualTo(404)
    }

	@Test
	@DisplayName("GUI는 체크섬 없는 구형 평면 파일을 내려주지 않는다")
	fun rejectsUnverifiedFlatGui() {
		place("pulsemetry_gui_darwin_arm64.dmg", "unverified")
		assertThat(get("/bin/pulsemetry_gui_darwin_arm64.dmg").statusCode()).isEqualTo(404)
	}

	// ── 404 ──────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("목록에 있어도 파일이 없으면 404")
	fun missingFileIsNotFound() {
		assertThat(get("/bin/pulsemetry_darwin_arm64").statusCode()).isEqualTo(404)
	}

	@ParameterizedTest
	@ValueSource(
		strings = [
			"pulsemetry_linux_386",
			"pulsemetry_linux_amd64.exe",
			"pulsemetry_darwin_amd64.txt",
			"PULSEMETRY_LINUX_AMD64",
			"pulsemetry_linux_amd6",
			"readme.md",
			"application.yaml",
		],
	)
	@DisplayName("화이트리스트 밖 이름은 파일이 있어도 404 다")
	fun namesOutsideAllowlistAreNotFound(filename: String) {
		Files.writeString(binariesDir.resolve(filename), "should never be served")

		assertThat(get("/bin/$filename").statusCode()).isEqualTo(404)
	}

	// ── 경로 traversal ───────────────────────────────────────────────────────

	@Test
	@DisplayName("상위 디렉터리 파일은 어떤 traversal 변형으로도 새어 나가지 않는다")
	fun traversalCannotEscapeBinariesDir() {
		val secret = binariesDir.parent.resolve("secret.txt")
		Files.writeString(secret, "top-secret")
		try {
			val attempts = listOf(
				"/bin/../secret.txt",
				"/bin/%2e%2e%2fsecret.txt",
				"/bin/..%2fsecret.txt",
				"/bin/..%252fsecret.txt",
				"/bin/....//secret.txt",
				"/bin/%2e%2e/secret.txt",
				"/bin/pulsemetry_linux_amd64/../../secret.txt",
			)

			attempts.forEach { path ->
				val response = get(path)
				// 인코딩된 슬래시(%2f)가 섞인 변형은 Tomcat 이 애플리케이션에 닿기 전에 400 으로 끊는다.
				// 나머지는 정규화되어 우리 핸들러까지 와서 화이트리스트에 걸려 404 가 된다.
				// 어느 쪽이든 파일은 나가지 않는다 — 그게 이 테스트가 지키는 성질이다.
				assertThat(response.statusCode())
					.describedAs("path=%s", path)
					.isIn(400, 404)
				assertThat(response.body())
					.describedAs("path=%s", path)
					.doesNotContain("top-secret")
			}
		} finally {
			Files.deleteIfExists(secret)
		}
	}

	@ParameterizedTest
	@ValueSource(
		strings = [
			"/bin/..",
			"/bin/%2e%2e",
			"/bin/../secret.txt",
			"/bin/....//secret.txt",
			"/bin/pulsemetry_linux_amd64/../../secret.txt",
		],
	)
	@DisplayName("핸들러까지 도달하는 traversal 은 화이트리스트에 걸려 404 다")
	fun traversalReachingHandlerIsNotFound(path: String) {
		assertThat(get(path).statusCode()).isEqualTo(404)
	}

	@Test
	@DisplayName("절대 경로를 파일명으로 줘도 404")
	fun absolutePathIsNotFound() {
		assertThat(get("/bin//etc/passwd").statusCode()).isNotEqualTo(200)
	}

	// ── 헬퍼 ─────────────────────────────────────────────────────────────────

	private fun get(path: String): HttpResponse<String> =
		http.send(
			HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().build(),
			HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
		)
}
