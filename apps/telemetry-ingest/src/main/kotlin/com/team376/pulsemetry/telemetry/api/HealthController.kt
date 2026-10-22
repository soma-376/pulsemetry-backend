package com.team376.pulsemetry.telemetry.api

import com.team376.pulsemetry.telemetry.config.KafkaConsumerConfig
import com.team376.pulsemetry.telemetry.config.TelemetryIngestProperties
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.ResponseEntity
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 로드밸런서·probe 용 생존 확인. **인증을 걸지 않는다** — 기본 닫힘 체인(`SecurityConfig`)이
 * 이 경로만 `permitAll` 한다. `kafka` 모드에도 있다(ADR 0066 Decision 7).
 *
 * **ClickHouse·RDB·S3·MSK 를 확인하지 않는다.** 스키마 적용에 실패해도 애플리케이션은 뜨고, 그 상태에서도
 * 수집·마스킹·아카이브는 제 일을 한다(ADR 0016). 의존 저장소의 일시 장애로 Pod 를 재시작하면 회복이 늦어질 뿐이다.
 *
 * **Kafka listener 의 가동 상태만 본다**(`dual`·`kafka` 모드). 컨테이너가 비정상으로 멈췄으면 소비가 조용히 끊긴 것이므로
 * 503 `listener_stopped` 다 — 재기동이 복구다. MSK 의 연결·인증 장애로는 멈추지 않는다(인증 예외는 컨테이너가 다시 시도한다).
 */
@RestController
class HealthController(
	@Qualifier(KafkaConsumerConfig.CONTAINER_BEAN)
	private val listener: ConcurrentMessageListenerContainer<ByteArray, ByteArray>,
	properties: TelemetryIngestProperties,
) {

	private val kafkaEnabled = properties.telemetry.ingest.mode.kafkaEnabled

	@GetMapping("/api/v1/healthz")
	fun healthz(): ResponseEntity<Map<String, String>> =
		if (kafkaEnabled && !(listener.isRunning && listener.isInExpectedState)) {
			ResponseEntity.status(503).body(mapOf("status" to "listener_stopped"))
		} else {
			ResponseEntity.ok(mapOf("status" to "ok"))
		}
}
