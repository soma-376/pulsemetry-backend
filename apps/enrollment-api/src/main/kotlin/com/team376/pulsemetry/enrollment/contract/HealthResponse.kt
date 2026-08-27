package com.team376.pulsemetry.enrollment.contract

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * `GET /v1/healthz` 응답 (PLAN.md §6.4).
 *
 * 정상: `{"status":"ok","checks":{"database":"ok"}}`
 * 장애: 503 + `{"status":"degraded","checks":{"database":"down"}}`
 *
 * 헬스체커가 초당 호출하므로 본문은 작게 유지하고, 내부 예외 메시지를 담지 않는다.
 */
data class HealthResponse(

	@JsonProperty("status")
	val status: String,

	@JsonProperty("checks")
	val checks: Map<String, String>,
) {
	companion object {

		private const val DATABASE = "database"

		fun ok(): HealthResponse = HealthResponse("ok", mapOf(DATABASE to "ok"))

		fun degraded(): HealthResponse = HealthResponse("degraded", mapOf(DATABASE to "down"))
	}
}
