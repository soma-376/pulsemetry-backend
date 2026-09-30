package com.team376.pulsemetry.enrollment.update

import com.fasterxml.jackson.annotation.JsonProperty
import com.team376.pulsemetry.enrollment.api.BinaryController
import com.team376.pulsemetry.enrollment.config.PulsemetryProperties
import com.team376.pulsemetry.enrollment.error.EnrollmentException
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
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
 * 서버가 배포하는 데몬 바이너리의 판 (허브 ADR 0011). 바이너리 디렉터리의 `pulsemetry_release.json` 이 말한다.
 *
 * 그 대상의 바이너리가 있고 SHA-256 이 메타데이터와 같을 때만 버전을 돌려준다. 메타데이터가 없거나 형식이 틀렸거나,
 * 파일이 없거나 해시가 다르면 null 이다 — **서버가 주지 못하는 버전을 최신이라고 말하지 않는다.**
 *
 * 메타데이터는 요청마다 읽는다(작은 파일이다). 해시는 파일의 크기·수정 시각·파일 키가 바뀔 때만 다시 계산한다.
 * 읽는 도중 파일이 교체되는 경합은 없는 파일과 같이 다룬다(바이너리 서빙과 같은 태도).
 */
class DaemonRelease(private val directory: Path, private val mapper: ObjectMapper) {
    private class Fingerprint(val size: Long, val modified: Long, val key: Any?, val sha256: String)

    private val hashes = ConcurrentHashMap<String, Fingerprint>()

    /** [filename] 은 호출자가 허용 목록으로 걸러 준 이름이다. 그 파일의 확인된 판을 돌려준다. */
    fun version(filename: String): String? = try {
        val metadata = metadata()
        val expected = metadata?.second?.get(filename)
        if (metadata == null || expected == null || sha256(filename) != expected) null else metadata.first
    } catch (_: IOException) {
        null
    }

    /** 버전과 파일명 → 해시. 계약의 형식을 어기면 null 이다. 모르는 키는 무시하지만 `sha256` 안의 키는 여섯 이름뿐이다. */
    private fun metadata(): Pair<String, Map<String, String>>? {
        val file = directory.resolve(METADATA_FILENAME)
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_METADATA_BYTES) return null
        val root = try { mapper.readTree(Files.readAllBytes(file)) } catch (_: JacksonException) { return null }
        if (root == null || !root.isObject) return null
        val version = root.get("version")?.takeIf { it.isString }?.stringValue()?.takeIf { SemanticVersion.parse(it) != null } ?: return null
        val sha256 = root.get("sha256")?.takeIf { it.isObject } ?: return null
        val hashes = LinkedHashMap<String, String>()
        for (name in sha256.propertyNames()) {
            val value = sha256.get(name)?.takeIf { it.isString }?.stringValue()
            if (name !in BinaryController.ALLOWED_FILENAMES || value == null || !SHA256.matches(value)) return null
            hashes[name] = value
        }
        return if (hashes.isEmpty()) null else version to hashes
    }

    private fun sha256(filename: String): String? {
        val file = directory.resolve(filename)
        if (!Files.isRegularFile(file)) return null
        val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
        val cached = hashes[filename]
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
        hashes[filename] = Fingerprint(attributes.size(), attributes.lastModifiedTime().toMillis(), attributes.fileKey(), sha256)
        return sha256
    }

    companion object {
        const val METADATA_FILENAME = "pulsemetry_release.json"
        private const val MAX_METADATA_BYTES = 64 * 1024L
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

data class CheckUpdatesResponse(
    @JsonProperty("latest_version") val latestVersion: String,
    @JsonProperty("update_available") val updateAvailable: Boolean,
)

/**
 * 데몬 업데이트 확인 (허브 `contracts/daemon-updates.md`, 허브 ADR 0011). 인증이 없다 — 데몬이 인증 정보를 보내지 않는다.
 *
 * 최신 버전은 이 서버가 `/bin/{filename}` 으로 배포하는 바이너리의 판이고 비교는 여기서 한다. 답할 수 없으면 404 다(데몬은 "미지원"으로 표시한다).
 * 해석할 수 없는 요청은 400 이다 — 순서를 알 수 없는 버전에 "업데이트 없음"이라고 답하지 않는다. 리다이렉트를 내지 않는다.
 */
@RestController
class UpdateCheckController(properties: PulsemetryProperties, mapper: ObjectMapper) {
    private val release = DaemonRelease(Path.of(properties.binaries.dir), mapper)

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
        val latest = release.version(filename) ?: throw EnrollmentException.updateUnavailable()
        val available = current < requireNotNull(SemanticVersion.parse(latest))
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(CheckUpdatesResponse(latest, available))
    }
}
