package com.team376.pulsemetry.enrollment.api

import com.team376.pulsemetry.enrollment.contract.HealthResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * `GET /v1/healthz` (PLAN.md §6.4).
 *
 * 인증이 없다. 헬스체커가 **초당** 호출하므로 이 클래스는 아무것도 로그하지 않는다 —
 * 로그 한 줄이 곧 하루 수만 줄이고, 그 소음에 진짜 사고가 묻힌다.
 * 실패는 503 이라는 응답 자체로 드러나며, 그걸 보는 건 모니터링의 몫이다.
 */
@RestController
@RequestMapping("/v1")
class HealthController(
	private val jdbcClient: JdbcClient,
) {

	@GetMapping("/healthz")
	fun healthz(): ResponseEntity<HealthResponse> =
		if (databaseIsReachable()) {
			ResponseEntity.ok(HealthResponse.ok())
		} else {
			ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(HealthResponse.degraded())
		}

	/**
	 * `SELECT 1` 프로브.
	 *
	 * 예외 종류를 가리지 않고 전부 "down" 으로 본다. 커넥션 풀 고갈·드라이버 오류·타임아웃은
	 * 서로 다른 예외를 던지지만 헬스체크 입장에서는 결과가 같고,
	 * **프로브가 예외를 밖으로 흘리면 500 이 되어 계약(503)을 어긴다.**
	 */
	@Suppress("TooGenericExceptionCaught", "SwallowedException")
	private fun databaseIsReachable(): Boolean =
		try {
			jdbcClient.sql("SELECT 1").query(Int::class.javaObjectType).single() == 1
		} catch (_: Exception) {
			false
		}
}
