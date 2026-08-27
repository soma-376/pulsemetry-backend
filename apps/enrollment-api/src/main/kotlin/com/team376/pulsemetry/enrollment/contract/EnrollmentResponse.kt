package com.team376.pulsemetry.enrollment.contract

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * `POST /v1/enroll` 성공(201) 응답 봉투.
 *
 * **최상위 키는 정확히 이 4개다.** 클라이언트가 `DisallowUnknownFields` 로 파싱하므로
 * "도움이 될" 필드(`member_email`, `tenant_name` …)를 하나라도 더하면 설치가 즉시 실패한다 (PLAN.md A4).
 * 세 토큰 필드는 전부 필수다 — 비어 있으면 클라이언트의 `Validate()` 가 거부한다.
 */
data class EnrollmentResponse(

	@JsonProperty("installation_id")
	val installationId: String,

	/** `pit_` + base64url(32 bytes). 응답에만 실리고 서버는 SHA-256 해시만 저장한다. 로그 금지 (R4). */
	@JsonProperty("installation_token")
	val installationToken: String,

	/** `ptt_` + base64url(32 bytes). OTLP Authorization 헤더에 들어간다. 로그 금지 (R4). */
	@JsonProperty("telemetry_token")
	val telemetryToken: String,

	@JsonProperty("manifest")
	val manifest: ManifestPayload,
)

/**
 * `POST /v1/installations/telemetry-token` 응답.
 *
 * **최상위 키는 정확히 이 2개다** (PLAN.md §6.3 / §9 7번).
 */
data class TelemetryTokenResponse(

	@JsonProperty("installation_id")
	val installationId: String,

	@JsonProperty("telemetry_token")
	val telemetryToken: String,
)

/**
 * non-2xx 응답 본문.
 *
 * CLI 는 이 본문을 **그대로 사용자에게 출력한다.** 그래서 [message] 는 한국어로,
 * 사용자가 다음에 무엇을 해야 할지 알 수 있게 쓴다 (PLAN.md §6.7).
 * 토큰·초대 코드 원본을 여기 담지 마라 (R4).
 */
data class ErrorResponse(

	@JsonProperty("error")
	val error: String,

	@JsonProperty("message")
	val message: String,
)
