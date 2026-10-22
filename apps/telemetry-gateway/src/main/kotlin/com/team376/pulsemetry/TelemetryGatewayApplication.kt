package com.team376.pulsemetry

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/**
 * OTLP 접수 Gateway (ADR 0066). telemetry JWT 를 검증하고 원문을 envelope 로 Kafka 에 발행한다.
 *
 * **루트 패키지에 있는 것이 의도다**(모듈 지도 3절 — 스캔 출발점). 앱 자신의 코드는 `com.team376.pulsemetry.telemetry.gateway`
 * 아래다 — 이 컨텍스트에 배포 단위가 둘이 되어 `<컨텍스트>.<인바운드>` 로 내렸다(ADR 0067 Decision 5).
 * 앱끼리는 의존하지 않으므로 ingest 의 메인 클래스와 한 클래스패스에 오르지 않는다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
class TelemetryGatewayApplication

fun main(args: Array<String>) {
	runApplication<TelemetryGatewayApplication>(*args)
}
