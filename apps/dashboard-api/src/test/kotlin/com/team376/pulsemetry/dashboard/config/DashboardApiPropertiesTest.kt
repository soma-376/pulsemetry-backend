package com.team376.pulsemetry.dashboard.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.StandardEnvironment
import java.time.Duration

/** 기본값 없는 필수 설정 (ADR 0022 §4·§5). */
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
		runner.withPropertyValues(*complete, "pulsemetry.dashboard.retry-after=").run { assertThat(it).hasFailed() }
	}

	@Test
	@DisplayName("retry-after 가 1초 미만이면 기동이 실패한다")
	fun subSecondRetryAfterFails() {
		runner.withPropertyValues(*complete, "pulsemetry.dashboard.retry-after=500ms").run { assertThat(it).hasFailed() }
	}

	@Test
	@DisplayName("값을 모두 주면 그 값으로 뜬다")
	fun everythingBinds() {
		runner.withPropertyValues(*complete).run {
			assertThat(it).hasNotFailed()
			val properties = it.getBean(DashboardApiProperties::class.java)
			assertThat(properties.retryAfter).isEqualTo(Duration.ofSeconds(3))
			assertThat(properties.clickhouse.source.maxResultRows).isEqualTo(100)
			assertThat(properties.rds.source.connectionTimeout).isEqualTo(Duration.ofSeconds(3))
			assertThat(properties.rds.source.password).isEmpty()
		}
	}

	@ParameterizedTest
	@ValueSource(
		strings = [
			"pulsemetry.dashboard.clickhouse.source.url",
			"pulsemetry.dashboard.clickhouse.source.database",
			"pulsemetry.dashboard.clickhouse.source.username",
			"pulsemetry.dashboard.clickhouse.source.query-timeout",
			"pulsemetry.dashboard.clickhouse.source.max-result-rows",
			"pulsemetry.dashboard.clickhouse.source.max-result-bytes",
			"pulsemetry.dashboard.clickhouse.cache.url",
			"pulsemetry.dashboard.clickhouse.cache.database",
			"pulsemetry.dashboard.clickhouse.cache.username",
			"pulsemetry.dashboard.clickhouse.cache.query-timeout",
			"pulsemetry.dashboard.rds.source.url",
			"pulsemetry.dashboard.rds.source.username",
			"pulsemetry.dashboard.rds.cache.url",
			"pulsemetry.dashboard.rds.cache.username",
		],
	)
	@DisplayName("원천 계정·상한 값이 비면 기동이 실패한다 — 기본값이 없다")
	fun blankSourceSettingFails(key: String) {
		runner.withPropertyValues(*complete.filterNot { it.startsWith("$key=") }.toTypedArray(), "$key=")
			.run { assertThat(it).hasFailed() }
	}

	private val complete = arrayOf(
		"pulsemetry.dashboard.retry-after=3s",
		"pulsemetry.dashboard.clickhouse.source.url=http://localhost:8123",
		"pulsemetry.dashboard.clickhouse.source.database=default",
		"pulsemetry.dashboard.clickhouse.source.username=dashboard_reader",
		"pulsemetry.dashboard.clickhouse.source.password=secret",
		"pulsemetry.dashboard.clickhouse.source.query-timeout=10s",
		"pulsemetry.dashboard.clickhouse.source.max-result-rows=100",
		"pulsemetry.dashboard.clickhouse.source.max-result-bytes=1000",
		"pulsemetry.dashboard.rds.source.url=jdbc:postgresql://localhost:5432/pulsemetry",
		"pulsemetry.dashboard.rds.source.username=dashboard_reader",
		"pulsemetry.dashboard.rds.source.password=",
		"pulsemetry.dashboard.rds.source.connection-timeout=3s",
		"pulsemetry.dashboard.clickhouse.cache.url=http://localhost:8123",
		"pulsemetry.dashboard.clickhouse.cache.database=dashboard_cache",
		"pulsemetry.dashboard.clickhouse.cache.username=dashboard_cache_writer",
		"pulsemetry.dashboard.clickhouse.cache.password=secret",
		"pulsemetry.dashboard.clickhouse.cache.query-timeout=30s",
		"pulsemetry.dashboard.rds.cache.url=jdbc:postgresql://localhost:5432/pulsemetry",
		"pulsemetry.dashboard.rds.cache.username=dashboard_cache_writer",
		"pulsemetry.dashboard.rds.cache.password=secret",
		"pulsemetry.dashboard.rds.cache.connection-timeout=3s",
	)

	@ParameterizedTest
	@ValueSource(strings = ["dashboard-cache", "cache; DROP", "1cache", "dashboard_cache.x"])
	@DisplayName("DB 이름이 식별자 형식이 아니면 기동이 실패한다 — 복사 SQL 에 식별자로 들어간다")
	fun databaseMustBeIdentifier(value: String) {
		runner.withPropertyValues(*complete, "pulsemetry.dashboard.clickhouse.cache.database=$value").run { assertThat(it).hasFailed() }
		runner.withPropertyValues(*complete, "pulsemetry.dashboard.clickhouse.source.database=$value").run { assertThat(it).hasFailed() }
	}
}
