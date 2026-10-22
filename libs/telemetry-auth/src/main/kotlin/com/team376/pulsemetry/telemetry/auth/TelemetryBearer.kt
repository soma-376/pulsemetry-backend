package com.team376.pulsemetry.telemetry.auth

/**
 * `Authorization: Bearer <토큰>` 에서 토큰을 꺼낸다. ingest 의 `TelemetryTokenAuthenticationFilter` 와 같은 문법이다 —
 * 스킴은 대소문자를 가리지 않고, 사이 공백은 여러 개여도 되며, 토큰에 공백이 섞이면 형식 오류다.
 *
 * 헤더가 없는 것과 빈 문자열은 같게 다룬다.
 */
public object TelemetryBearer {

	private val BEARER = Regex("""Bearer\s+(\S+)""", RegexOption.IGNORE_CASE)

	/** @throws TelemetryJwtException `missing_bearer`·`malformed_bearer`. */
	public fun extract(header: String?): String {
		if (header.isNullOrEmpty()) throw TelemetryJwtException(TelemetryJwtRejection.MISSING_BEARER)
		return BEARER.matchEntire(header)?.groupValues?.get(1)
			?: throw TelemetryJwtException(TelemetryJwtRejection.MALFORMED_BEARER)
	}
}
