package com.team376.pulsemetry.telemetry.otlp

/**
 * 수신한 OTLP 요청. 서블릿·WebFlux 어느 쪽에도 매이지 않는다 — HTTP 라우팅은 조립 앱이 한다(ADR 0011).
 * 앱이 자기 요청 타입에서 이 다섯을 뽑아 넘긴다.
 */
public class OtlpHttpRequest(
	public val method: String,
	public val path: String,
	public val contentType: String?,
	public val contentEncoding: String?,
	public val body: ByteArray,
)

/** 그대로 내보낼 응답. 본문 바이트가 곧 계약이다 — [OtlpResponses] KDoc 참고. */
public class OtlpHttpResponse(
	public val status: Int,
	public val contentType: String,
	public val body: ByteArray,
)
