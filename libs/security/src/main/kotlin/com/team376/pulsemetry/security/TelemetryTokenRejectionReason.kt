package com.team376.pulsemetry.security

/**
 * OTLP 경로에서 `ptt_` 토큰을 거부하는 사유.
 *
 * **사유는 클라이언트에게 노출하지 않는다.** 열한 가지가 전부 같은 401 본문으로 접힌다
 * ([TelemetryTokenAuthenticationEntryPoint]). 어느 단계에서 걸렸는지 알려 주면 유효한 토큰을
 * 찾는 탐색이 쉬워지기 때문이다. 사유는 서버 로그에만 남는다.
 *
 * 이식 원본: `ai-telemetry-pipeline` `apps/auth-proxy/src/auth/credential.types.ts`.
 */
enum class TelemetryTokenRejectionReason(val code: String) {

	/** `Authorization` 헤더가 없거나 비어 있다. */
	MISSING_BEARER("missing_bearer"),

	/** 헤더가 `Bearer <토큰>` 문법에 맞지 않는다. */
	MALFORMED_BEARER("malformed_bearer"),

	/** 해시에 해당하는 토큰이 없다. 폐기가 아니라 애초에 모르는 값이다. */
	TOKEN_UNKNOWN("token_unknown"),

	TOKEN_REVOKED("token_revoked"),
	INSTALLATION_REVOKED("installation_revoked"),

	/** installation 이 `active` 가 아니다. 폐기 시각이 없는 `revoked` 상태가 여기 걸린다. */
	INSTALLATION_INACTIVE("installation_inactive"),

	MEMBER_SUSPENDED("member_suspended"),

	/** enroll 로 `invited → active` 전환이 아직 일어나지 않았다 (명세 §4.2). */
	MEMBER_INVITED("member_invited"),

	TENANT_DELETED("tenant_deleted"),
	TENANT_SUSPENDED("tenant_suspended"),
	TENANT_TERMINATED("tenant_terminated"),
}
