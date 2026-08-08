package com.team376.pulsemetry.enrollment.api

import com.team376.pulsemetry.enrollment.contract.TelemetryTokenResponse
import com.team376.pulsemetry.enrollment.service.TelemetryTokenService
import org.springframework.http.HttpHeaders
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 설치된 클라이언트가 장기 자격증명으로 telemetry token 을 다시 받아 가는 엔드포인트.
 *
 * 헤더가 없어도 400 이 아니라 401 이어야 하므로 `required = false` 로 받아 서비스에서 판단한다 —
 * 인증 실패의 사유를 상태코드로 구분해 주면 그것도 정보 노출이다.
 */
@RestController
@RequestMapping("/v1")
class TelemetryTokenController(
	private val telemetryTokenService: TelemetryTokenService,
) {

	@PostMapping("/installations/telemetry-token")
	fun reissue(
		@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
	): TelemetryTokenResponse = telemetryTokenService.reissue(authorization)
}
