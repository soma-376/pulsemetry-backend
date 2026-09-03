package com.team376.pulsemetry.telemetry.api

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 로드밸런서용 생존 확인. **인증을 걸지 않는다** — 필터 체인의 `securityMatcher` 가
 * OTLP 세 경로만 잡으므로 이 경로는 Spring Security 를 지나지 않는다.
 *
 * **ClickHouse 를 확인하지 않는다.** 스키마 적용에 실패해도 애플리케이션은 뜨고, 그 상태에서도
 * 수집·마스킹·아카이브는 제 일을 한다(ADR 0016). 여기서 ClickHouse 를 물으면 적재만 죽은
 * 상황에 태스크가 통째로 교체되어 복구 원천까지 끊긴다.
 */
@RestController
class HealthController {

	@GetMapping("/v1/healthz")
	fun healthz(): Map<String, String> = mapOf("status" to "ok")
}
