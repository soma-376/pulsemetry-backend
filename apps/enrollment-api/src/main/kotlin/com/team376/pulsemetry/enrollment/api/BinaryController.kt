package com.team376.pulsemetry.enrollment.api

import com.team376.pulsemetry.enrollment.config.PulsemetryProperties
import com.team376.pulsemetry.enrollment.update.DaemonRelease
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * CLI·GUI 배포 파일 서빙 (허브 ADR 0017, 로컬 ADR 0053).
 *
 * 방어는 **화이트리스트 하나뿐**이다. 요청한 이름이 CLI [ALLOWED_FILENAMES] 또는 GUI [GUI_FILENAMES] 중 하나와
 * 정확히 같지 않으면 그 자리에서 404 다.
 *
 * `..` 를 문자열 치환으로 지우거나 경로를 정규화해서 막으려 하지 마라 (A9).
 * 인코딩 변형(`%2e%2e%2f`, `..%252f`, 유니코드 정규화 …)은 끝없이 나오고,
 * 그 게임에서는 언젠가 진다. 허용 목록과의 동등 비교는 그런 변형이 애초에 통과할 수 없다.
 *
 * 파일의 출처는 ADR 0053 이다. 바이너리 디렉터리에 telemetryctl 릴리스 디렉터리(`v<SemVer>`)가 있으면 공개 이름을 그 릴리스의
 * 데몬 자산으로 대응하고 `SHA256SUMS` 로 확인한 파일만 내려준다 — 업데이트 확인이 답하는 판과 같은 파일이다([DaemonRelease]).
 * 릴리스 디렉터리가 없으면 공개 이름 그대로의 파일을 내려준다(그 판은 알 수 없어 업데이트 확인은 답하지 않는다).
 */
@RestController
class BinaryController(
	private val properties: PulsemetryProperties,
	private val release: DaemonRelease,
) {

	@GetMapping("/bin/{filename}")
	fun download(@PathVariable filename: String): ResponseEntity<Resource> {
		if (filename !in ALLOWED_FILENAMES && filename !in GUI_FILENAMES) return ResponseEntity.notFound().build()

		val file: Path = if (release.present()) {
			release.asset(filename)?.file ?: return ResponseEntity.notFound().build()
		} else {
			if (filename in GUI_FILENAMES) return ResponseEntity.notFound().build()
			Path.of(properties.binaries.dir).resolve(filename)
		}
		if (!Files.isRegularFile(file)) return ResponseEntity.notFound().build()

		// isRegularFile 확인과 size 조회 사이에 파일이 교체될 수 있다(바이너리 재배포 중 열리는 창).
		// 그 경합은 없는 파일과 같은 상황이다 — 500 대신 404 로 답한다.
		val size = try {
			Files.size(file)
		} catch (_: IOException) {
			return ResponseEntity.notFound().build()
		}

		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_OCTET_STREAM)
			.contentLength(size)
			.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
			.body(FileSystemResource(file))
	}

	companion object {
		/** 부트스트랩 스크립트가 만들어 내는 이름 6개. 이 목록이 곧 계약이다. */
		val ALLOWED_FILENAMES: Set<String> = setOf(
			"pulsemetry_windows_amd64.exe",
			"pulsemetry_windows_arm64.exe",
			"pulsemetry_darwin_amd64",
			"pulsemetry_darwin_arm64",
			"pulsemetry_linux_amd64",
			"pulsemetry_linux_arm64",
		)
        /** 제거 도구를 포함한 GUI 패키지. CLI 업데이트 대상 목록과 구분한다. */
        val GUI_FILENAMES: Set<String> = setOf(
            "pulsemetry_gui_windows_amd64.exe", "pulsemetry_gui_windows_arm64.exe",
            "pulsemetry_gui_darwin_amd64.dmg", "pulsemetry_gui_darwin_arm64.dmg",
            "pulsemetry_gui_linux_amd64.AppImage", "pulsemetry_gui_linux_arm64.AppImage",
        )

	}
}
