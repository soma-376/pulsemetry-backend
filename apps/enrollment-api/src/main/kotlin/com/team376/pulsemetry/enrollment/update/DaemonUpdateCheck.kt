package com.team376.pulsemetry.enrollment.update

import com.fasterxml.jackson.annotation.JsonProperty
import com.team376.pulsemetry.enrollment.api.BinaryController
import com.team376.pulsemetry.enrollment.config.PulsemetryProperties
import com.team376.pulsemetry.enrollment.error.EnrollmentException
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.io.IOException
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap

/**
 * SemVer 2.0.0 버전. `v` 를 붙이지 않는다. 순서는 SemVer 의 우선순위 규칙이다 —
 * 사전 릴리스는 같은 번호의 정식 판보다 낮고, 빌드 메타데이터는 순서에 영향을 주지 않는다 (허브 `contracts/daemon-updates.md` §4).
 */
class SemanticVersion private constructor(private val core: List<BigInteger>, private val prerelease: List<String>) : Comparable<SemanticVersion> {

    override fun compareTo(other: SemanticVersion): Int {
        for (i in core.indices) core[i].compareTo(other.core[i]).let { if (it != 0) return it }
        // 사전 릴리스가 없는 쪽이 높다.
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) return other.prerelease.size.compareTo(prerelease.size).coerceIn(-1, 1)
        for (i in 0 until minOf(prerelease.size, other.prerelease.size)) identifier(prerelease[i], other.prerelease[i]).let { if (it != 0) return it }
        // 앞이 모두 같으면 식별자가 더 많은 쪽이 높다.
        return prerelease.size.compareTo(other.prerelease.size)
    }

    /** 숫자끼리는 숫자로, 숫자는 문자보다 낮게, 문자끼리는 ASCII 순으로 비교한다. */
    private fun identifier(a: String, b: String): Int {
        val numericA = a.all(Char::isDigit)
        val numericB = b.all(Char::isDigit)
        return when {
            numericA && numericB -> BigInteger(a).compareTo(BigInteger(b))
            numericA -> -1
            numericB -> 1
            else -> a.compareTo(b).coerceIn(-1, 1)
        }
    }

    companion object {
        private val PATTERN = Regex(
            """(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)""" +
                """(?:-((?:0|[1-9][0-9]*|[0-9]*[a-zA-Z-][0-9a-zA-Z-]*)(?:\.(?:0|[1-9][0-9]*|[0-9]*[a-zA-Z-][0-9a-zA-Z-]*))*))?""" +
                """(?:\+[0-9a-zA-Z-]+(?:\.[0-9a-zA-Z-]+)*)?""",
        )

        /** 형식이 아니면 null 이다. 앞뒤 공백도 형식 위반이다. */
        fun parse(text: String): SemanticVersion? {
            val match = PATTERN.matchEntire(text) ?: return null
            val prerelease = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
            return SemanticVersion(match.groupValues.subList(1, 4).map(::BigInteger), prerelease)
        }
    }
}

/**
 * 서버가 배포하는 데몬 바이너리와 그 판 (허브 ADR 0011, ADR 0053). 판과 해시는 telemetryctl 릴리스 산출물 그대로가 말한다.
 *
 * 바이너리 디렉터리 안의 `v<SemVer>` 디렉터리가 릴리스 하나다 — 그 태그의 GitHub Release 에서 받은 `SHA256SUMS` 와
 * 데몬 자산 `pulsemetry_cli_{os}_{arch}[.exe]` 를 담는다. 여럿이면 판이 가장 높은 것이 이 서버의 릴리스다.
 * 공개 이름 `pulsemetry_{os}_{arch}[.exe]`(허용 목록)는 같은 대상의 데몬 자산으로 대응한다.
 *
 * 자산은 파일이 있고 SHA-256 이 `SHA256SUMS` 와 같을 때만 돌려준다. `SHA256SUMS` 가 없거나 형식이 틀렸거나, 자산이 없거나 해시가 다르면 null 이다 —
 * **서버가 주지 못하는 버전을 최신이라고 말하지 않고, 확인하지 못한 파일을 그 버전으로 내려주지 않는다.**
 *
 * 디렉터리와 `SHA256SUMS` 는 요청마다 읽는다(작다). 해시는 파일의 크기·수정 시각·파일 키가 바뀔 때만 다시 계산한다.
 * 읽는 도중 파일이 교체되는 경합은 없는 파일과 같이 다룬다(바이너리 서빙과 같은 태도).
 */
@Component
class DaemonRelease(properties: PulsemetryProperties) {
    private val directory: Path = Path.of(properties.binaries.dir)

    /** 확인된 데몬 자산. [version] 은 태그에서 `v` 를 뗀 SemVer 다. */
    class Asset(val version: String, val file: Path)

    private class Fingerprint(val size: Long, val modified: Long, val key: Any?, val sha256: String)

    private val hashes = ConcurrentHashMap<Path, Fingerprint>()

    /** 릴리스 디렉터리가 하나라도 있는가. 없으면 바이너리 서빙은 평면 파일을 쓰고 업데이트 확인은 답하지 않는다(ADR 0053). */
    fun present(): Boolean = latest() != null

    /** [filename] 은 호출자가 허용 목록으로 걸러 준 공개 이름이다. 이 서버의 릴리스에서 확인한 그 대상의 자산을 돌려준다. */
    fun asset(filename: String): Asset? = try {
        val release = latest()
        val assetName = filename.replaceFirst(PUBLIC_PREFIX, ASSET_PREFIX)
        val expected = release?.let { sums(it.second)?.get(assetName) }
        val file = release?.second?.resolve(assetName)
        if (release == null || expected == null || file == null || sha256(file) != expected) null else Asset(release.first, file)
    } catch (_: IOException) {
        null
    }

    /** 판이 가장 높은 `v<SemVer>` 디렉터리. 없으면 null. */
    private fun latest(): Pair<String, Path>? = try {
        if (!Files.isDirectory(directory)) null
        else Files.list(directory).use { it.toList() }
            .filter { Files.isDirectory(it) && it.fileName.toString().startsWith("v") }
            .mapNotNull { path -> path.fileName.toString().substring(1).let { version -> SemanticVersion.parse(version)?.let { Triple(version, it, path) } } }
            .maxByOrNull { it.second }
            ?.let { it.first to it.third }
    } catch (_: IOException) {
        null
    }

    /** `SHA256SUMS` 의 자산 이름 → 해시. 한 줄에 `<소문자 hex 64자>`, 공백 둘, 자산 이름. 형식이 틀린 줄이 하나라도 있으면 null 이다. */
    private fun sums(release: Path): Map<String, String>? {
        val file = release.resolve(SUMS_FILENAME)
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_SUMS_BYTES) return null
        val lines = Files.readString(file).split('\n').let { if (it.lastOrNull() == "") it.dropLast(1) else it }
        val sums = LinkedHashMap<String, String>()
        for (line in lines) {
            val match = SUMS_LINE.matchEntire(line) ?: return null
            if (sums.put(match.groupValues[2], match.groupValues[1]) != null) return null
        }
        return sums.ifEmpty { null }
    }

    private fun sha256(file: Path): String? {
        if (!Files.isRegularFile(file)) return null
        val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
        val cached = hashes[file]
        if (cached != null && cached.size == attributes.size() && cached.modified == attributes.lastModifiedTime().toMillis() &&
            cached.key == attributes.fileKey()) return cached.sha256
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val sha256 = HexFormat.of().formatHex(digest.digest())
        hashes[file] = Fingerprint(attributes.size(), attributes.lastModifiedTime().toMillis(), attributes.fileKey(), sha256)
        return sha256
    }

    companion object {
        const val SUMS_FILENAME = "SHA256SUMS"
        private const val PUBLIC_PREFIX = "pulsemetry_"
        private const val ASSET_PREFIX = "pulsemetry_cli_"
        private const val MAX_SUMS_BYTES = 64 * 1024L
        private val SUMS_LINE = Regex("([0-9a-f]{64})  ([A-Za-z0-9._-]+)")
    }
}

data class CheckUpdatesResponse(
    @JsonProperty("latest_version") val latestVersion: String,
    @JsonProperty("update_available") val updateAvailable: Boolean,
)

/**
 * 데몬 업데이트 확인 (허브 `contracts/daemon-updates.md`, 허브 ADR 0011, ADR 0053). 인증이 없다 — 데몬이 인증 정보를 보내지 않는다.
 *
 * 최신 버전은 이 서버가 `/bin/{filename}` 으로 배포하는 바이너리의 판이고 비교는 여기서 한다. 답할 수 없으면 404 다(데몬은 "미지원"으로 표시한다).
 * 해석할 수 없는 요청은 400 이다 — 순서를 알 수 없는 버전에 "업데이트 없음"이라고 답하지 않는다. 리다이렉트를 내지 않는다.
 */
@RestController
class UpdateCheckController(private val release: DaemonRelease) {

    @GetMapping("/api/v1/check-updates")
    fun check(
        @RequestParam("current_version", required = false) currentVersion: String?,
        @RequestParam("platform", required = false) platform: String?,
        @RequestParam("architecture", required = false) architecture: String?,
    ): ResponseEntity<CheckUpdatesResponse> {
        if (currentVersion.isNullOrEmpty() || platform.isNullOrEmpty() || architecture.isNullOrEmpty()) throw EnrollmentException.malformedUpdateCheck()
        val current = SemanticVersion.parse(currentVersion) ?: throw EnrollmentException.malformedUpdateCheck()
        // 파일명은 허용 목록과의 동등 비교로만 정한다. 목록에 없는 대상은 이 서버가 배포하지 않는 대상이다.
        val filename = "pulsemetry_${platform}_$architecture" + if (platform == "windows") ".exe" else ""
        if (filename !in BinaryController.ALLOWED_FILENAMES) throw EnrollmentException.updateUnavailable()
        val latest = release.asset(filename)?.version ?: throw EnrollmentException.updateUnavailable()
        val available = current < requireNotNull(SemanticVersion.parse(latest))
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(CheckUpdatesResponse(latest, available))
    }
}
