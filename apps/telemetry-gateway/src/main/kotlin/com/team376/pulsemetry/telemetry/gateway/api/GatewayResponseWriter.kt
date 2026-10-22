package com.team376.pulsemetry.telemetry.gateway.api

import com.team376.pulsemetry.telemetry.gateway.config.TelemetryGatewayProperties
import com.team376.pulsemetry.telemetry.otlp.OtlpHttpResponse
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component

/**
 * OTLP/HTTP 응답을 서블릿 응답에 그대로 쓴다. 503 에만 `Retry-After` 를 붙인다 — ingest 의 `OtlpResponseWriter` 와 같은 규칙이다.
 * 본문 바이트가 계약이므로 메시지 컨버터를 거치지 않는다.
 */
@Component
class GatewayResponseWriter(properties: TelemetryGatewayProperties) {

	private val retryAfterSeconds = properties.telemetry.gateway.retryAfter.seconds.toString()

	fun write(response: HttpServletResponse, result: OtlpHttpResponse) {
		response.status = result.status
		response.setHeader(HttpHeaders.CONTENT_TYPE, result.contentType)
		// 데몬은 이 값을 하한으로 쓰고 15초에서 자른다. 503 에만 붙인다 (허브 ADR 0006).
		if (result.status == HttpServletResponse.SC_SERVICE_UNAVAILABLE) {
			response.setHeader(HttpHeaders.RETRY_AFTER, retryAfterSeconds)
		}
		response.setContentLength(result.body.size)
		response.outputStream.write(result.body)
	}
}
