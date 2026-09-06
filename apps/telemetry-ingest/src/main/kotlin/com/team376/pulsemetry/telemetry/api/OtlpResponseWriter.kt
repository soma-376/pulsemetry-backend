package com.team376.pulsemetry.telemetry.api

import com.team376.pulsemetry.telemetry.collector.OtlpHttpResponse
import com.team376.pulsemetry.telemetry.config.TelemetryIngestProperties
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component

/**
 * 수집 모듈의 [OtlpHttpResponse] 를 서블릿 응답에 그대로 쓴다.
 *
 * 쓰는 자리가 둘이다 — 컨트롤러와, 인증 조회 장애를 503 으로 돌리는 필터 핸들러. `Retry-After` 를
 * 붙이는 규칙이 한 곳에 있어야 두 503 이 같은 모양이다.
 */
@Component
class OtlpResponseWriter(properties: TelemetryIngestProperties) {

	private val retryAfterSeconds = properties.telemetry.ingest.retryAfter.seconds.toString()

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
