package com.team376.pulsemetry.dashboard.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.StandardEnvironment
import java.time.Duration

/** 기본값 없는 필수 설정 (ADR 0022 §5). */
class DashboardApiPropertiesTest {

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(DashboardApiProperties::class)
	class Binding

	/**
	 * 테스트 JVM 에는 build.gradle.kts 가 주입한 시스템 프로퍼티가 있다. 그 출처를 떼어 이 클래스가 주는 값만 보게 한다.
	 */
	private val runner = ApplicationContextRunner()
		.withInitializer { it.environment.propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME) }
		.withUserConfiguration(Binding::class.java)

	@Test
	@DisplayName("retry-after 가 없으면 기동이 실패한다")
	fun missingRetryAfterFails() {
		runner.run { assertThat(it).hasFailed() }
	}

	@Test
	@DisplayName("retry-after 가 비어 있으면 기동이 실패한다 — application.yaml 의 환경변수 자리가 빈 경우")
	fun blankRetryAfterFails() {
		runner.withPropertyValues("pulsemetry.dashboard.retry-after=").run { assertThat(it).hasFailed() }
	}

	@Test
	@DisplayName("retry-after 가 1초 미만이면 기동이 실패한다")
	fun subSecondRetryAfterFails() {
		runner.withPropertyValues("pulsemetry.dashboard.retry-after=500ms").run { assertThat(it).hasFailed() }
	}

	@Test
	@DisplayName("retry-after 를 주면 그 값으로 뜬다")
	fun retryAfterBinds() {
		runner.withPropertyValues("pulsemetry.dashboard.retry-after=3s").run {
			assertThat(it).hasNotFailed()
			assertThat(it.getBean(DashboardApiProperties::class.java).retryAfter).isEqualTo(Duration.ofSeconds(3))
		}
	}
}
