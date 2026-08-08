package com.team376.pulsemetry.enrollment.contract

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * `POST /v1/enroll` 요청 본문.
 *
 * `telemetryctl/contracts/enrollment-envelope.schema.json` 의 `$defs.enroll_request` 와 1:1 이다.
 * 계약이 서버를 규정한다 — 여기가 맞지 않으면 **서버를 고친다** (PLAN.md R3).
 *
 * 스키마에 없는 필드가 오면 400 `invalid_request` 다 (`FAIL_ON_UNKNOWN_PROPERTIES=true`).
 * 반대로 여기 있는 deprecated 필드를 빼면 안 된다 — Go 클라이언트의 `Invite` 에는 `omitempty` 가 없어서
 * 요청에 **항상** `"invite": ""` 가 실려 온다. 이걸 unknown 으로 취급하면 모든 설치가 실패한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class EnrollRequest(

	@JsonProperty("code")
	val code: String? = null,

	@JsonProperty("platform")
	val platform: String? = null,

	@JsonProperty("architecture")
	val architecture: String? = null,

	@JsonProperty("hostname")
	val hostname: String? = null,

	@JsonProperty("client_version")
	val clientVersion: String? = null,

	/** Deprecated: [code] 로 대체. Go 클라이언트가 항상 직렬화하므로 받아들여야 한다. */
	@JsonProperty("invite")
	val invite: String? = null,

	/** Deprecated: [clientVersion] 으로 대체. */
	@JsonProperty("installer_version")
	val installerVersion: String? = null,

	/** Deprecated: [platform] 으로 대체. */
	@JsonProperty("operating_environment")
	val operatingEnvironment: String? = null,

	/** Deprecated. 받아들이되 무시한다. */
	@JsonProperty("device_id")
	val deviceId: String? = null,

	/** Deprecated. 받아들이되 무시한다. */
	@JsonProperty("tools_detected")
	val toolsDetected: List<String>? = null,
) {
	/** [code] 가 비면 deprecated [invite] 로 fallback 한다. 둘 다 비면 null → 400 (PLAN.md §6.2). */
	fun effectiveCode(): String? = code.orNullIfBlank() ?: invite.orNullIfBlank()

	/** [platform] 이 비면 deprecated [operatingEnvironment] 로 fallback 한다. */
	fun effectivePlatform(): String? = platform.orNullIfBlank() ?: operatingEnvironment.orNullIfBlank()

	/** [clientVersion] 이 비면 deprecated [installerVersion] 으로 fallback 한다. */
	fun effectiveClientVersion(): String? = clientVersion.orNullIfBlank() ?: installerVersion.orNullIfBlank()

	private fun String?.orNullIfBlank(): String? = this?.takeIf { it.isNotBlank() }
}
