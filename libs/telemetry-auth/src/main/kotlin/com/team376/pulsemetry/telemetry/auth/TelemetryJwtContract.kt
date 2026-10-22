package com.team376.pulsemetry.telemetry.auth

import java.time.Duration

/**
 * telemetry JWT 의 고정 계약(ADR 0065 Decision 1). **설정이 아니다** — 발급자와 모든 검증자가 같은 값을 봐야 하고,
 * 환경마다 바꿀 이유가 없다. 값을 바꾸면 이미 발급된 토큰이 전부 거부되므로 바꾸는 것은 ADR 개정이다.
 *
 * 사용자 로그인 JWT(ADR 0018)와 겹치는 값이 하나도 없게 정했다 — `typ`·`aud`·`iss` 중 하나만 맞아도 다른 경로에서
 * 통과하지 않는다. 키도 따로다.
 */
public object TelemetryJwtContract {

	/** header `typ`. 사용자 JWT 의 `JWT` 와 구별한다. */
	public const val TYPE: String = "pulsemetry-telemetry+jwt"

	public const val ISSUER: String = "pulsemetry-enrollment"

	/** 단일 값이다. 배열에 다른 audience 가 섞인 토큰은 거부한다. */
	public const val AUDIENCE: String = "pulsemetry-telemetry-ingest"

	public const val SCOPE: String = "telemetry:write"

	public const val TENANT_ID_CLAIM: String = "tenant_id"

	public const val SCOPE_CLAIM: String = "scope"

	/** 수명. `exp - iat` 가 이보다 길면 거부한다. */
	public val LIFETIME: Duration = Duration.ofHours(3)

	/** 발급자와 검증자의 시계 오차 허용. `[iat - 30s, exp + 30s]` 가 유효 구간이다. */
	public val CLOCK_SKEW: Duration = Duration.ofSeconds(30)

	/** 이보다 긴 문자열은 파싱하지 않는다. 정상 토큰은 1 KiB 안팎이다. */
	public const val MAX_TOKEN_LENGTH: Int = 8192

	/** RSA 키의 최소 크기. */
	public const val MIN_RSA_BITS: Int = 2048
}
