package com.team376.pulsemetry.dashboard.support

import com.team376.pulsemetry.dashboard.authentication.DashboardAuthenticator
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/**
 * 테스트 컨텍스트에서 인증 포트를 [TestDashboardAuthenticator] 로 바꿔 끼운다. 기본 구현 빈은 그대로 있고
 * `@Primary` 가 주입을 가져간다 — 빈 덮어쓰기를 켜지 않는다.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestAuthenticationConfig {

	@Bean
	@Primary
	fun testDashboardAuthenticator(): DashboardAuthenticator = TestDashboardAuthenticator()
}
