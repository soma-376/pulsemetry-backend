package com.team376.pulsemetry.dashboard.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * 시간을 주입 가능한 의존성으로 다룬다.
 *
 * 세션 만료·기간 해석·실행 타임아웃처럼 시각이 결과를 바꾸는 로직이 `Instant.now()` 를 직접 부르면
 * 테스트가 실제 시계에 묶인다. `:apps:enrollment-api` 의 `ClockConfig` 와 같은 취지다.
 */
@Configuration(proxyBeanMethods = false)
class DashboardClockConfig {
    @Bean fun clock(): Clock = Clock.systemUTC()
}
