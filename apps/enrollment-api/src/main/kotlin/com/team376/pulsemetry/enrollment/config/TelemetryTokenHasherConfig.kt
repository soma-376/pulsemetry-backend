package com.team376.pulsemetry.enrollment.config

import com.team376.pulsemetry.security.TelemetryTokenHasher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * [TelemetryTokenHasher] 를 빈으로 올린다.
 *
 * 해셔는 `:libs:security` 에 있다 — 토큰을 **발급**하는 이 앱과 OTLP 경로에서 **검증**하는 쪽이
 * 같은 키로 같은 연산을 해야 하고, 정의가 두 벌이 되면 발급된 전 토큰이 조용히 401 이 되기 때문이다
 * (허브 `contracts/enrollment-api.md` §4).
 *
 * 라이브러리 모듈은 스테레오타입을 달지 않으므로 빈 등록은 앱의 몫이다 (ADR 0011).
 * 키가 비어 있으면 해셔 생성자가 막아 애플리케이션이 뜨지 않는다 — 규약은 그대로다.
 */
@Configuration
class TelemetryTokenHasherConfig {

	@Bean
	fun telemetryTokenHasher(properties: PulsemetryProperties): TelemetryTokenHasher =
		TelemetryTokenHasher(properties.tokenHashSecret)
}
