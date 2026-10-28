package com.team376.pulsemetry.enrollment.api

import com.team376.pulsemetry.enrollment.error.EnrollmentException
import com.team376.pulsemetry.enrollment.service.BootstrapInvitationValidator
import com.team376.pulsemetry.enrollment.service.BootstrapScripts
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 한 줄 설치 명령의 스크립트(ADR 0005). 조회는 코드를 소비하지 않고 최종 enroll이 다시 검증한다.
 * 공개 전에 infra WAF가 세 등록 경로의 요청 예산을 공유하도록 배포돼 있어야 한다.
 */
@RestController
class BootstrapController(
	private val bootstrapScripts: BootstrapScripts,
	private val invitations: BootstrapInvitationValidator,
) {

	@GetMapping("/windows", produces = [TEXT_PLAIN_UTF8])
	fun windows(@RequestParam(required = false) code: String?): ResponseEntity<String> =
		respond(code, bootstrapScripts::windows, bootstrapScripts::windowsFailure)

	@GetMapping("/unix", produces = [TEXT_PLAIN_UTF8])
	fun unix(@RequestParam(required = false) code: String?): ResponseEntity<String> =
		respond(code, bootstrapScripts::unix, bootstrapScripts::unixFailure)

    private fun respond(raw: String?, success: (String) -> String, failure: (EnrollmentException) -> String): ResponseEntity<String> {
        val code = try {
            invitations.validate(raw)
        } catch (error: EnrollmentException) {
            // 이 검사가 만드는 고정 문구만 스크립트로 보낸다. DB·서버 장애는 5xx로 전파한다.
            return script(failure(error))
        }
        return script(success(code))
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
