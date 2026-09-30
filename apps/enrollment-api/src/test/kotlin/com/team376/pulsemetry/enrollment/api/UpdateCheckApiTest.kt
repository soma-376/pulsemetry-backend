package com.team376.pulsemetry.enrollment.api

import com.team376.pulsemetry.enrollment.config.PulsemetryProperties
import com.team376.pulsemetry.enrollment.support.ContractSchemas
import com.team376.pulsemetry.enrollment.update.SemanticVersion
import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.HexFormat

/**
 * `GET /api/v1/check-updates` (허브 `contracts/daemon-updates.md`, 허브 ADR 0011). 기대값은 계약에서 온다.
 *
 * 최신 버전은 서버가 배포하는 바이너리의 판이고, 그 판은 바이너리 옆의 `pulsemetry_release.json` 이 말한다.
 * 바이너리 디렉터리는 [BinaryApiTest] 와 같은 곳이다 — 테스트마다 비우고 채운다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresContainerConfig::class)
class UpdateCheckApiTest {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var properties: PulsemetryProperties
    @Autowired private lateinit var mapper: ObjectMapper
    private val http = HttpClient.newHttpClient()
    private lateinit var directory: Path

    @BeforeEach fun setUp() {
        directory = Path.of(properties.binaries.dir)
        Files.createDirectories(directory)
        Files.list(directory).use { paths -> paths.forEach(Files::delete) }
    }

    private fun sha256(content: String) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.toByteArray()))

    /** 바이너리들을 놓고 그 해시를 담은 메타데이터를 쓴다. 메타데이터는 계약 스키마로 확인한다. */
    private fun release(version: String, vararg binaries: Pair<String, String>) {
        binaries.forEach { (name, content) -> Files.writeString(directory.resolve(name), content) }
        val metadata = mapper.writeValueAsString(mapOf("version" to version, "sha256" to binaries.associate { (name, content) -> name to sha256(content) }))
        assertThat(ContractSchemas.validate(ContractSchemas.releaseMetadataSchema(), metadata)).describedAs(metadata).isEmpty()
        metadata(metadata)
    }

    private fun metadata(json: String) { Files.writeString(directory.resolve("pulsemetry_release.json"), json) }

    private fun check(version: String? = "0.1.0", platform: String? = "darwin", architecture: String? = "arm64", extra: String = ""): HttpResponse<String> {
        val query = listOfNotNull(version?.let { "current_version" to it }, platform?.let { "platform" to it }, architecture?.let { "architecture" to it })
            .joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, StandardCharsets.UTF_8)}" }
        return get("/api/v1/check-updates?$query$extra")
    }

    private fun get(path: String, vararg headers: Pair<String, String>): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path")).header("Accept", "application/json")
        headers.forEach { (name, value) -> builder.header(name, value) }
        return http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun available(response: HttpResponse<String>): Boolean {
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200)
        return mapper.readTree(response.body()).path("update_available").booleanValue()
    }

    private fun rejected(response: HttpResponse<String>, status: Int, code: String) {
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(status)
        assertThat(mapper.readTree(response.body()).path("error").asString()).isEqualTo(code)
        // 확인하지 못했을 때 데몬이 읽는 두 키를 내지 않는다.
        assertThat(response.body()).doesNotContain("latest_version", "update_available")
    }

    // ── 응답 계약 ────────────────────────────────────────────────────────────

    @Test fun `응답은 데몬이 읽는 두 키의 JSON 문서 하나이고 계약 스키마를 만족한다`() {
        release("0.2.0", "pulsemetry_darwin_arm64" to "darwin-arm64-0.2.0")
        val response = check("0.1.0")

        assertThat(response.statusCode()).isEqualTo(200)
        val errors = ContractSchemas.validate(ContractSchemas.checkUpdatesResponseSchema(), response.body())
        assertThat(errors).describedAs(ContractSchemas.describe(errors)).isEmpty()
        val json = mapper.readTree(response.body())
        assertThat(json.propertyNames()).containsExactlyInAnyOrder("latest_version", "update_available")
        assertThat(json.path("latest_version").isString).isTrue()
        assertThat(json.path("latest_version").asString()).isEqualTo("0.2.0")
        assertThat(json.path("update_available").isBoolean).isTrue()
        assertThat(json.path("update_available").booleanValue()).isTrue()
        // 데몬은 문서 뒤에 다른 내용이 있으면 응답을 무효로 본다.
        assertThat(response.body().trim()).isEqualTo(mapper.writeValueAsString(json))
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json")
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store")
        assertThat(response.body().toByteArray().size).isLessThan(64 * 1024)
    }

    @Test fun `인증을 요구하지 않고 인증 헤더가 있어도 같은 답이다`() {
        release("0.2.0", "pulsemetry_darwin_arm64" to "binary")
        assertThat(available(check("0.2.0"))).isFalse()
        assertThat(available(get("/api/v1/check-updates?current_version=0.2.0&platform=darwin&architecture=arm64", "Authorization" to "Bearer anything"))).isFalse()
        // 모르는 쿼리는 무시한다.
        assertThat(available(check("0.1.0", extra = "&channel=beta"))).isTrue()
    }

    // ── 비교 ─────────────────────────────────────────────────────────────────

    @Test fun `데몬의 버전이 배포 판보다 낮을 때만 업데이트가 있다`() {
        release("0.2.0", "pulsemetry_darwin_arm64" to "binary")
        assertThat(available(check("0.1.9"))).isTrue()
        assertThat(available(check("0.2.0"))).isFalse()
        assertThat(available(check("0.2.1"))).isFalse()
        assertThat(available(check("1.0.0"))).isFalse()
        // 버전을 주입하지 않은 빌드는 0.1.0 으로 보고한다. 따로 취급하지 않는다.
        assertThat(available(check("0.1.0"))).isTrue()
        // 번호는 문자열이 아니라 숫자로 비교한다.
        release("0.10.0", "pulsemetry_darwin_arm64" to "binary")
        assertThat(available(check("0.9.0"))).isTrue()
        assertThat(available(check("0.10.0"))).isFalse()
    }

    @Test fun `사전 릴리스는 같은 번호의 정식 판보다 낮고 빌드 메타데이터는 순서에 영향을 주지 않는다`() {
        release("0.2.0", "pulsemetry_darwin_arm64" to "binary")
        assertThat(available(check("0.2.0-rc.1"))).isTrue()
        assertThat(available(check("0.2.0+build.7"))).isFalse()
        assertThat(available(check("0.3.0-alpha"))).isFalse()

        // 서버가 사전 릴리스를 배포하고 있으면 그 판이 이 서버의 최신이다.
        release("0.3.0-rc.2", "pulsemetry_darwin_arm64" to "binary")
        assertThat(mapper.readTree(check("0.2.0").body()).path("latest_version").asString()).isEqualTo("0.3.0-rc.2")
        assertThat(available(check("0.2.0"))).isTrue()
        assertThat(available(check("0.3.0-rc.1"))).isTrue()
        assertThat(available(check("0.3.0-rc.2"))).isFalse()
        assertThat(available(check("0.3.0-rc.10"))).isFalse()
        assertThat(available(check("0.3.0"))).isFalse()
        assertThat(available(check("0.3.0-rc.2+sha.abc"))).isFalse()

        release("1.0.0+build.2", "pulsemetry_darwin_arm64" to "binary")
        assertThat(available(check("1.0.0+build.1"))).isFalse()
        assertThat(available(check("1.0.0"))).isFalse()
    }

    @Test fun `SemVer 우선순위 규칙의 예시 순서를 따른다`() {
        // SemVer 2.0.0 §11 의 예시.
        val ordered = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0",
            "1.0.1", "1.1.0", "2.0.0", "2.1.0", "2.1.1", "10.0.0")
        val parsed = ordered.map { requireNotNull(SemanticVersion.parse(it)) { it } }
        for (i in parsed.indices) for (j in parsed.indices) {
            assertThat(Integer.signum(parsed[i].compareTo(parsed[j]))).describedAs("%s vs %s", ordered[i], ordered[j]).isEqualTo(Integer.signum(i.compareTo(j)))
        }
        assertThat(SemanticVersion.parse("1.0.0+a")!!.compareTo(SemanticVersion.parse("1.0.0+b")!!)).isEqualTo(0)
        // 숫자 식별자는 문자 식별자보다 낮다.
        assertThat(SemanticVersion.parse("1.0.0-1")!!).isLessThan(SemanticVersion.parse("1.0.0-a")!!)
        for (invalid in listOf("v1.0.0", "1.0", "1", "01.0.0", "1.0.0-", "1.0.0-01", "1.0.0-a..b", "1.0.0+", " 1.0.0", "1.0.0 ", "dev", "", "1.0.0.0", "1.0.0-β")) {
            assertThat(SemanticVersion.parse(invalid)).describedAs(invalid).isNull()
        }
        // 계약 스키마의 버전 형식과 같은 것을 받는다.
        for (version in ordered + listOf("v1.0.0", "01.0.0", "1.0.0-01", "1.0.0+build.5", "dev", "1.0")) {
            val schemaValid = ContractSchemas.validate(ContractSchemas.checkUpdatesResponseSchema(), """{"latest_version":"$version","update_available":true}""").isEmpty()
            assertThat(SemanticVersion.parse(version) != null).describedAs(version).isEqualTo(schemaValid)
        }
    }

    // ── 대상 ─────────────────────────────────────────────────────────────────

    @Test fun `플랫폼과 아키텍처로 그 대상의 바이너리를 고르고 윈도우는 exe다`() {
        release("0.2.0", *BinaryController.ALLOWED_FILENAMES.map { it to "content of $it" }.toTypedArray())
        for ((platform, architecture) in listOf("darwin" to "amd64", "darwin" to "arm64", "linux" to "amd64", "linux" to "arm64", "windows" to "amd64", "windows" to "arm64")) {
            assertThat(available(check("0.1.0", platform, architecture))).describedAs("%s/%s", platform, architecture).isTrue()
        }
    }

    @Test fun `배포하지 않는 대상은 404이고 최신이라고 답하지 않는다`() {
        release("0.2.0", "pulsemetry_darwin_arm64" to "binary", "pulsemetry_linux_amd64" to "binary")
        // 메타데이터에도 디렉터리에도 없는 대상.
        rejected(check("0.1.0", "windows", "amd64"), 404, "not_found")
        // 여섯 파일명으로 이어지지 않는 값.
        for ((platform, architecture) in listOf("freebsd" to "amd64", "darwin" to "386", "macos" to "arm64", "Darwin" to "arm64", "darwin" to "arm64.exe",
            "../.." to "arm64", "darwin" to "arm64/../../secret", "darwin_arm64" to "x", "windows.exe" to "amd64")) {
            rejected(check("0.1.0", platform, architecture), 404, "not_found")
        }
    }

    // ── 메타데이터 ───────────────────────────────────────────────────────────

    @Test fun `메타데이터가 없으면 바이너리가 있어도 404다`() {
        Files.writeString(directory.resolve("pulsemetry_darwin_arm64"), "binary")
        rejected(check(), 404, "not_found")
    }

    @Test fun `형식이 틀린 메타데이터는 없는 것과 같다`() {
        Files.writeString(directory.resolve("pulsemetry_darwin_arm64"), "binary")
        val hash = sha256("binary")
        val broken = listOf(
            "not json", "[]", "null", "",
            """{"sha256":{"pulsemetry_darwin_arm64":"$hash"}}""",
            """{"version":"v0.2.0","sha256":{"pulsemetry_darwin_arm64":"$hash"}}""",
            """{"version":"latest","sha256":{"pulsemetry_darwin_arm64":"$hash"}}""",
            """{"version":2,"sha256":{"pulsemetry_darwin_arm64":"$hash"}}""",
            """{"version":"0.2.0"}""",
            """{"version":"0.2.0","sha256":{}}""",
            """{"version":"0.2.0","sha256":"$hash"}""",
            """{"version":"0.2.0","sha256":{"pulsemetry_darwin_arm64":"${hash.uppercase()}"}}""",
            """{"version":"0.2.0","sha256":{"pulsemetry_darwin_arm64":"${hash.dropLast(1)}"}}""",
            """{"version":"0.2.0","sha256":{"pulsemetry_darwin_arm64":null}}""",
            """{"version":"0.2.0","sha256":{"pulsemetry_darwin_arm64":"$hash","pulsemetry_freebsd_amd64":"$hash"}}""",
        )
        for (json in broken) {
            // 테스트의 "틀린 형식"이 계약 스키마로도 틀린 것인지 확인한다(JSON 이 아닌 것은 스키마 이전에 틀렸다).
            if (json.startsWith("{")) assertThat(ContractSchemas.validate(ContractSchemas.releaseMetadataSchema(), json)).describedAs(json).isNotEmpty()
            metadata(json)
            rejected(check(), 404, "not_found")
        }
        // 모르는 키는 무시한다.
        metadata("""{"version":"0.2.0","sha256":{"pulsemetry_darwin_arm64":"$hash"},"built_at":"2026-09-30T00:00:00Z","notes":{"url":"x"}}""")
        assertThat(available(check())).isTrue()
    }

    @Test fun `바이너리의 해시가 메타데이터와 다르면 404이고 파일이 맞게 바뀌면 다시 답한다`() {
        release("0.2.0", "pulsemetry_darwin_arm64" to "binary-0.2.0")
        assertThat(available(check())).isTrue()

        // 메타데이터만 새 판으로 바뀌고 바이너리는 옛 파일이다.
        metadata(mapper.writeValueAsString(mapOf("version" to "0.3.0", "sha256" to mapOf("pulsemetry_darwin_arm64" to sha256("binary-0.3.0")))))
        rejected(check(), 404, "not_found")

        // 같은 크기의 새 파일로 바뀐다. 수정 시각이 달라지면 해시를 다시 계산한다.
        val binary = directory.resolve("pulsemetry_darwin_arm64")
        Files.writeString(binary, "binary-0.3.0")
        Files.setLastModifiedTime(binary, FileTime.fromMillis(Files.getLastModifiedTime(binary).toMillis() + 5_000))
        val response = check("0.2.0")
        assertThat(available(response)).isTrue()
        assertThat(mapper.readTree(response.body()).path("latest_version").asString()).isEqualTo("0.3.0")

        // 바이너리가 사라지면 다시 404 다.
        Files.delete(binary)
        rejected(check(), 404, "not_found")
    }

    @Test fun `메타데이터에 없는 바이너리는 디렉터리에 있어도 답하지 않는다`() {
        release("0.2.0", "pulsemetry_linux_amd64" to "linux")
        Files.writeString(directory.resolve("pulsemetry_darwin_arm64"), "stray binary")
        rejected(check("0.1.0", "darwin", "arm64"), 404, "not_found")
        assertThat(available(check("0.1.0", "linux", "amd64"))).isTrue()
    }

    // ── 요청 ─────────────────────────────────────────────────────────────────

    @Test fun `쿼리가 빠졌거나 버전을 해석할 수 없으면 400이고 업데이트 없음으로 답하지 않는다`() {
        release("0.2.0", "pulsemetry_darwin_arm64" to "binary")
        rejected(check(version = null), 400, "invalid_request")
        rejected(check(platform = null), 400, "invalid_request")
        rejected(check(architecture = null), 400, "invalid_request")
        rejected(get("/api/v1/check-updates"), 400, "invalid_request")
        rejected(check(version = ""), 400, "invalid_request")
        rejected(check(platform = ""), 400, "invalid_request")
        for (version in listOf("v0.2.0", "dev", "0.2", "01.2.3", "0.2.0-", "latest", " 0.2.0", "0.2.0 ", "0.2.0-rc..1")) {
            rejected(check(version), 400, "invalid_request")
        }
    }

    @Test fun `리다이렉트를 내지 않고 메타데이터 파일을 서빙하지 않는다`() {
        release("0.2.0", "pulsemetry_darwin_arm64" to "binary")
        for (path in listOf("/api/v1/check-updates/?current_version=0.1.0&platform=darwin&architecture=arm64", "/api/v1/check-updates",
            "/api/v1/CHECK-UPDATES?current_version=0.1.0&platform=darwin&architecture=arm64", "/api/v1/check-updates?current_version=0.1.0&platform=darwin&architecture=arm64")) {
            assertThat(get(path).statusCode() in 300..399).describedAs(path).isFalse()
        }
        assertThat(get("/bin/pulsemetry_release.json").statusCode()).isEqualTo(404)
        val post = http.send(HttpRequest.newBuilder(URI("http://localhost:$port/api/v1/check-updates?current_version=0.1.0&platform=darwin&architecture=arm64"))
            .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
        assertThat(post.statusCode()).isEqualTo(405)
    }
}
