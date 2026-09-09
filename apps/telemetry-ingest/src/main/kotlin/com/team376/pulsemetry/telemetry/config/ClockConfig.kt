package com.team376.pulsemetry.telemetry.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * 시간을 주입 가능한 의존성으로 다룬다. `:apps:enrollment-api` 와 같은 이유다.
 *
 * 여기서는 `S3ArchiveWriter` 의 키 파티션이 이 시계를 쓴다 — 파티션 시각이 UTC 로 고정돼야
 * 재처리 배치가 범위를 잡을 수 있다(ADR 0012).
 */
@Configuration(proxyBeanMethods = false)
class ClockConfig {

	@Bean
	fun clock(): Clock = Clock.systemUTC()
}
