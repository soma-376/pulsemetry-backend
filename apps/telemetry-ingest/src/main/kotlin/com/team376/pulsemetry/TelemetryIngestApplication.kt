package com.team376.pulsemetry

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/**
 * OTLP 수집 · 마스킹 · 변환 · 보강 · 적재를 한 프로세스로 띄운다 (허브 ADR 0005).
 *
 * **루트 패키지에 있는 것이 의도다** (모듈 지도 3절). 보강 단계가 쓰는 JPA 가
 * `com.team376.pulsemetry.persistence.enrollment` 아래에 있어, 이 클래스를
 * `...telemetry` 에 두면 컴포넌트 스캔에 걸리지 않아 `@EntityScan`·`@EnableJpaRepositories` 를
 * 손으로 적어야 한다. `:apps:enrollment-api` 의 `PulsemetryApplication` 이 선례이고,
 * 앱끼리는 의존하지 않으므로 두 메인 클래스가 한 클래스패스에 오르지 않는다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
class TelemetryIngestApplication

fun main(args: Array<String>) {
	runApplication<TelemetryIngestApplication>(*args)
}
