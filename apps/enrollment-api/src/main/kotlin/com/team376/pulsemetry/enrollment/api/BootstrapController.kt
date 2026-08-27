package com.team376.pulsemetry.enrollment.api

import com.team376.pulsemetry.enrollment.error.EnrollmentException
import com.team376.pulsemetry.enrollment.secret.InvitationCode
import com.team376.pulsemetry.enrollment.service.BootstrapScripts
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 한 줄 설치 명령이 내려받는 부트스트랩 스크립트 (PLAN.md §6.6).
 *
 * **경로에 `/v1` 이 없다.** 사용자가 터미널에 붙여넣는 URL 이라 짧아야 한다.
 *
 * 여기서는 코드를 **정규식으로만** 검증하고 DB 를 보지 않는다.
 * 존재하는 코드와 없는 코드에 다르게 응답하면, 공격자가 이 엔드포인트로 코드를 하나씩
 * 찔러 보며 유효한 코드를 찾아낼 수 있다 — 형식만 맞으면 스크립트는 똑같이 나간다.
 */
@RestController
class BootstrapController(
	private val bootstrapScripts: BootstrapScripts,
) {

	@GetMapping("/windows", produces = [TEXT_PLAIN_UTF8])
	fun windows(@RequestParam(required = false) code: String?): ResponseEntity<String> =
		script(bootstrapScripts.windows(validated(code)))

	@GetMapping("/unix", produces = [TEXT_PLAIN_UTF8])
	fun unix(@RequestParam(required = false) code: String?): ResponseEntity<String> =
		script(bootstrapScripts.unix(validated(code)))

	private fun validated(code: String?): String {
		if (code.isNullOrEmpty()) throw EnrollmentException.missingCode()
		if (!InvitationCode.matches(code)) throw EnrollmentException.malformedCode()
		return code
	}

	/** 초대 코드가 박힌 스크립트는 중간 캐시에 남으면 안 된다. */
	private fun script(body: String): ResponseEntity<String> =
		ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(TEXT_PLAIN_UTF8))
			.header(HttpHeaders.CACHE_CONTROL, "no-store")
			.body(body)

	private companion object {
		const val TEXT_PLAIN_UTF8 = "text/plain;charset=UTF-8"
	}
}
