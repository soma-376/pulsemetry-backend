package com.team376.pulsemetry.telemetry.api

import com.team376.pulsemetry.telemetry.collector.OtlpHttpRequest
import com.team376.pulsemetry.telemetry.collector.OtlpIngestHandler
import com.team376.pulsemetry.telemetry.config.TelemetryIngestProperties
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * OTLP 세 경로를 수집 모듈에 넘긴다. **라우팅만 한다.**
 *
 * ## Spring 이 먼저 거부하면 안 된다
 *
 * - `consumes` 를 걸지 않는다. 415 는 수집 모듈이 상위와 같은 문자열로 내야 하는데,
 *   `consumes` 를 걸면 Spring 이 자기 415 를 먼저 낸다.
 * - `method` 를 제한하지 않는다. 405 도 마찬가지로 수집 모듈의 몫이다.
 * - 본문을 `@RequestBody` 로 받지 않는다. 메시지 컨버터가 `Content-Type` 을 먼저 해석해
 *   같은 문제를 만든다. 서블릿 스트림에서 직접 읽는다.
 * - **`@RestControllerAdvice` 를 두지 않는다.** 성공·오류 본문이 바이트 계약이다
 *   (`0a 00` / `{"partialSuccess":{}}` / `google.rpc.Status`).
 *
 * 응답을 직접 쓰고 `Unit` 을 돌려주므로 컨버터도 콘텐트 협상도 지나지 않는다.
 */
@RestController
class OtlpController(
	private val handler: OtlpIngestHandler,
	properties: TelemetryIngestProperties,
) {

	private val maxRequestBytes = properties.telemetry.ingest.maxRequestBytes
	private val retryAfterSeconds = properties.telemetry.ingest.retryAfter.seconds.toString()

	@RequestMapping(path = ["/v1/logs", "/v1/traces", "/v1/metrics"])
	fun ingest(request: HttpServletRequest, response: HttpServletResponse) {
		val body = readBody(request) ?: return payloadTooLarge(response)

		val result = handler.handle(
			OtlpHttpRequest(
				method = request.method,
				path = request.requestURI,
				contentType = request.getHeader(HttpHeaders.CONTENT_TYPE),
				contentEncoding = request.getHeader(HttpHeaders.CONTENT_ENCODING),
				body = body,
			),
		)

		response.status = result.status
		response.setHeader(HttpHeaders.CONTENT_TYPE, result.contentType)
		// 데몬은 이 값을 하한으로 쓰고 15초에서 자른다. 503 에만 붙인다 (허브 ADR 0006).
		if (result.status == HttpServletResponse.SC_SERVICE_UNAVAILABLE) {
			response.setHeader(HttpHeaders.RETRY_AFTER, retryAfterSeconds)
		}
		response.setContentLength(result.body.size)
		response.outputStream.write(result.body)
	}

	/**
	 * 압축을 풀기 **전** 바이트에 상한을 건다. 넘으면 `null` 이다.
	 *
	 * 수집 모듈의 상한은 압축을 푼 뒤에 걸리므로, 이것이 없으면 거대한 요청이 통째로 힙에
	 * 올라온다. 값은 구 auth-proxy 의 `MAX_OTLP_BODY_SIZE`(10 MiB)를 물려받았다.
	 */
	private fun readBody(request: HttpServletRequest): ByteArray? {
		if (request.contentLengthLong > maxRequestBytes) return null
		// 상한 + 1 만큼 읽어 "넘쳤는지"를 판정한다. 상한만 읽으면 딱 맞는 요청과 구분되지 않는다.
		val read = request.inputStream.readNBytes(Math.toIntExact(maxRequestBytes + 1))
		return if (read.size > maxRequestBytes) null else read
	}

	/** 수집 모듈에 413 경로가 없다. 프레임워크 층의 거부라 405·415 와 같은 `text/plain` 이다. */
	private fun payloadTooLarge(response: HttpServletResponse) {
		val body = "413 request entity too large".toByteArray(Charsets.UTF_8)
		response.status = HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE
		response.setHeader(HttpHeaders.CONTENT_TYPE, "text/plain")
		response.setContentLength(body.size)
		response.outputStream.write(body)
	}
}
