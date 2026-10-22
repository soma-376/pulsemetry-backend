package com.team376.pulsemetry.telemetry.gateway.support

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Gateway 통합 테스트 기반 — **DB 없이** Kafka 하나와 공개키 하나로 뜬다. 모든 애너테이션이 여기 있다 — 하위 클래스가
 * 설정을 더하면 컨텍스트 캐시가 갈린다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractGatewayIntegrationTest {

	companion object {
		@JvmStatic
		@DynamicPropertySource
		fun gatewayProperties(registry: DynamicPropertyRegistry) {
			registry.add("pulsemetry.telemetry.kafka.bootstrap-servers") { GatewayTestKafka.shared.bootstrapServers }
			registry.add("pulsemetry.telemetry.kafka.topic") { GatewayTestKafka.TOPIC }
			registry.add("pulsemetry.telemetry-jwt.public-key-files.${GatewayTestKafka.KID}") { GatewayTestKafka.PUBLIC_PEM.toString() }
		}
	}
}
