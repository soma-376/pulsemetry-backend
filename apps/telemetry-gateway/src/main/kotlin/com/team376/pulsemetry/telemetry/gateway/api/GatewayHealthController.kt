package com.team376.pulsemetry.telemetry.gateway.api

import com.team376.pulsemetry.telemetry.otlp.OtlpResponses
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 프로세스 생존 확인 — liveness·readiness·ALB health 가 같은 경로를 본다. **Kafka 를 확인하지 않는다** — MSK 의 일시 장애로
 * Pod 를 재시작하면 회복이 늦어질 뿐이다. 발행 실패는 요청마다 503 으로 드러난다(ADR 0066).
 */
@RestController
class GatewayHealthController(private val writer: GatewayResponseWriter) {

	@GetMapping("/api/v1/healthz")
	fun healthz(): Map<String, String> = mapOf("status" to "ok")

	/**
	 * 계약 밖 경로는 `404 text/plain` 이다 — ingest 의 기본 닫힘 응답과 같은 바이트다(허브 계약 §8). 전체 와일드카드 매핑은 가장 덜
	 * 구체적이라 OTLP·health 매핑이 먼저 잡는다.
	 */
	@RequestMapping("/**")
	fun notFound(response: HttpServletResponse) = writer.write(response, OtlpResponses.notFound())
}
