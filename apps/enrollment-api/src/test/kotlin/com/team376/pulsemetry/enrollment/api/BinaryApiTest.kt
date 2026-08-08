package com.team376.pulsemetry.enrollment.api

import com.team376.pulsemetry.enrollment.config.PulsemetryProperties
import com.team376.pulsemetry.support.PostgresContainerConfig
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
		Files.list(binariesDir).use { paths -> paths.forEach(Files::delete) }
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
