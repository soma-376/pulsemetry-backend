package com.team376.pulsemetry.dashboard.api

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 로드밸런서용 생존 확인. **인증을 걸지 않는다** — 기본 닫힘 체인(`SecurityConfig`)이 이 경로만 연다.
 *
 * **저장소를 확인하지 않는다**(ADR 0022 §6). ClickHouse·RDS 장애는 조회 응답의 503 으로 드러나야 하고,
 * 여기서 물으면 저장소 장애가 태스크 교체로 번진다.
 */
@RestController
class HealthController {

	@GetMapping("/api/v1/healthz")
	fun healthz(): Map<String, String> = mapOf("status" to "ok")
}
