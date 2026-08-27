package com.team376.pulsemetry.enrollment.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * 시간을 주입 가능한 의존성으로 다룬다.
 *
 * 만료 판정처럼 시각이 결과를 바꾸는 로직에서 `Instant.now()` 를 직접 부르면
 * 테스트가 실제 시계에 묶인다. DB 의 `now()` 대신 애플리케이션 시계를 쓰는 이유는
 * 조건부 UPDATE 의 `:now` 파라미터와 사유 판별의 기준 시각이 같아야 하기 때문이다.
 */
@Configuration
class ClockConfig {

	@Bean
	fun clock(): Clock = Clock.systemUTC()
}
