package com.team376.pulsemetry.enrollment.api

import com.team376.pulsemetry.enrollment.config.PulsemetryProperties
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files
import java.nio.file.Path

/**
 * CLI 바이너리 서빙 (PLAN.md §6.6).
 *
 * 방어는 **화이트리스트 하나뿐**이다. 요청한 이름이 [ALLOWED_FILENAMES] 의 6개 중 하나와
 * 정확히 같지 않으면 그 자리에서 404 다.
 *
 * `..` 를 문자열 치환으로 지우거나 경로를 정규화해서 막으려 하지 마라 (A9).
 * 인코딩 변형(`%2e%2e%2f`, `..%252f`, 유니코드 정규화 …)은 끝없이 나오고,
 * 그 게임에서는 언젠가 진다. 허용 목록과의 동등 비교는 그런 변형이 애초에 통과할 수 없다.
 */
@RestController
class BinaryController(
	private val properties: PulsemetryProperties,
) {

	@GetMapping("/bin/{filename}")
	fun download(@PathVariable filename: String): ResponseEntity<Resource> {
		if (filename !in ALLOWED_FILENAMES) return ResponseEntity.notFound().build()

		val file: Path = Path.of(properties.binaries.dir).resolve(filename)
		if (!Files.isRegularFile(file)) return ResponseEntity.notFound().build()

		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_OCTET_STREAM)
			.contentLength(Files.size(file))
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
	}
}
