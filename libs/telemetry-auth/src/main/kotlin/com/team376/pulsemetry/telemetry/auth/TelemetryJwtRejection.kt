package com.team376.pulsemetry.telemetry.auth

/**
 * telemetry JWT 를 거부한 사유. **응답에 싣지 않는다** — 401 본문은 하나다(허브 ADR 0001·0006). 로그의 `reason` 값이 [code] 다.
 *
 * 코드는 로그 metric filter 가 term 으로 읽으므로 공백 없는 소문자 밑줄 표기를 유지한다.
 */
public enum class TelemetryJwtRejection(public val code: String) {

	/** `Authorization` 헤더가 없거나 비어 있다. ingest 의 `missing_bearer` 와 같은 코드다. */
	MISSING_BEARER("missing_bearer"),

	/** 헤더가 `Bearer <토큰>` 문법이 아니다. */
	MALFORMED_BEARER("malformed_bearer"),

	/** compact JWS 로 파싱되지 않거나 너무 길다. `alg=none` 도 여기다. */
	MALFORMED("jwt_malformed"),

	/** RS256 이 아니다. */
	ALGORITHM("jwt_algorithm"),

	/** header `typ` 가 telemetry 전용 값이 아니다 — 사용자 JWT 가 여기서 걸린다. */
	TYPE("jwt_type"),

	/** `crit`·`jku`·`jwk`·`x5u`·`x5c` 처럼 쓰지 않는 header 가 있다. 원격·내장 키를 따르지 않는다. */
	HEADER("jwt_header"),

	/** `kid` 가 없거나 공개키 목록에 없다. */
	KID_UNKNOWN("jwt_kid_unknown"),

	SIGNATURE("jwt_signature"),
	ISSUER("jwt_issuer"),
	AUDIENCE("jwt_audience"),
	SCOPE("jwt_scope"),

	/** `sub`·`tenant_id`·`jti`·`iat`·`exp` 중 하나가 없다. */
	CLAIM_MISSING("jwt_claim_missing"),

	/** UUID·정수 초 형식이 아니다. */
	CLAIM_FORMAT("jwt_claim_format"),

	/** `exp <= iat` 이거나 수명이 3시간을 넘는다. */
	LIFETIME("jwt_lifetime"),

	/** 판정 시각이 `iat - 30s` 보다 이르거나 `nbf` 가 오차 밖으로 미래다. */
	NOT_YET_VALID("jwt_not_yet_valid"),

	/** 판정 시각이 `exp + 30s` 보다 늦다. */
	EXPIRED("jwt_expired"),
}

/**
 * telemetry JWT 거부. 메시지에는 사유 코드만 싣는다 — 토큰 원문·claim 값을 담지 않으므로 로그에 그대로 남겨도 된다.
 * 스택을 만들지 않는다. 인증 실패는 정상 흐름이고 원인 예외도 담지 않는다(파서 메시지에 토큰 조각이 섞일 수 있다).
 */
public class TelemetryJwtException(public val reason: TelemetryJwtRejection) :
	RuntimeException("telemetry jwt rejected: ${reason.code}", null, false, false)
