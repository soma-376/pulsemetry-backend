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
			"pulsemetry.dashboard.snapshot.build-timeout",
			"pulsemetry.dashboard.snapshot.purge-grace",
			"pulsemetry.dashboard.snapshot.max-concurrent-builds",
			"pulsemetry.dashboard.snapshot.max-copy-rows",
			"pulsemetry.dashboard.snapshot.max-copy-bytes",
			"pulsemetry.dashboard.snapshot.cleanup-interval",
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
		"pulsemetry.dashboard.snapshot.build-timeout=60s",
		"pulsemetry.dashboard.snapshot.purge-grace=0s",
		"pulsemetry.dashboard.snapshot.max-concurrent-builds=2",
		"pulsemetry.dashboard.snapshot.max-copy-rows=1000000",
		"pulsemetry.dashboard.snapshot.max-copy-bytes=1000000000",
		"pulsemetry.dashboard.snapshot.cleanup-interval=5m",
		"pulsemetry.dashboard.members.idle-days=30",
		"pulsemetry.dashboard.ingest.window=15m",
		"pulsemetry.dashboard.ingest.delayed-after=5m",
		"pulsemetry.dashboard.ingest.down-after=3h",
		"pulsemetry.dashboard.completeness.settle-after=1h",
		"pulsemetry.dashboard.seats.stale-after=26h",
		"pulsemetry.dashboard.alerts.evaluation-interval=1m",
		"pulsemetry.dashboard.alerts.lease=10m",
	)

	@ParameterizedTest
	@ValueSource(strings = ["", "0s", "-1m"])
	@DisplayName("알림 평가 주기·선점 기한이 없거나 0 이하면 기동이 실패한다 — 기본값이 없다 (ADR 0051)")
	fun alertSettingsAreRequired(value: String) {
		for (key in listOf("pulsemetry.dashboard.alerts.evaluation-interval", "pulsemetry.dashboard.alerts.lease")) {
			runner.withPropertyValues(*complete.filterNot { it.startsWith("$key=") }.toTypedArray()).run { assertThat(it).hasFailed() }
			runner.withPropertyValues(*complete, "$key=$value").run { assertThat(it).hasFailed() }
		}
		runner.withPropertyValues(*complete).run { assertThat(it).hasNotFailed() }
	}

	@ParameterizedTest
	@ValueSource(strings = ["", "0s", "-1h"])
	@DisplayName("좌석 원장의 낡음 기준이 없거나 0 이하면 기동이 실패한다 — 기본값이 없다 (ADR 0048)")
	fun seatStaleAfterIsRequired(value: String) {
		runner.withPropertyValues(*complete.filterNot { it.startsWith("pulsemetry.dashboard.seats.stale-after=") }.toTypedArray())
			.run { assertThat(it).hasFailed() }
		runner.withPropertyValues(*complete, "pulsemetry.dashboard.seats.stale-after=$value").run { assertThat(it).hasFailed() }
	}

	@ParameterizedTest
	@ValueSource(strings = ["", "0s", "-1m"])
	@DisplayName("기간 완전성의 확정 대기 시간이 없거나 0 이하면 기동이 실패한다 — 기본값이 없다 (ADR 0042)")
	fun settleAfterIsRequired(value: String) {
		runner.withPropertyValues(*complete.filterNot { it.startsWith("pulsemetry.dashboard.completeness.settle-after=") }.toTypedArray())
			.run { assertThat(it).hasFailed() }
		runner.withPropertyValues(*complete, "pulsemetry.dashboard.completeness.settle-after=$value").run { assertThat(it).hasFailed() }
	}

	@ParameterizedTest
	@ValueSource(strings = ["pulsemetry.dashboard.ingest.window", "pulsemetry.dashboard.ingest.delayed-after", "pulsemetry.dashboard.ingest.down-after"])
	@DisplayName("수집 상태 판정의 임계값이 없거나 비면 기동이 실패한다 — 기본값이 없다 (ADR 0041)")
	fun ingestThresholdsAreRequired(key: String) {
		val withoutKey = complete.filterNot { it.startsWith("$key=") }.toTypedArray()
		runner.withPropertyValues(*withoutKey).run { assertThat(it).hasFailed() }
		runner.withPropertyValues(*withoutKey, "$key=").run { assertThat(it).hasFailed() }
	}

	@ParameterizedTest
	@ValueSource(
		strings = [
			// 창은 응답에 분 단위로 나간다.
			"pulsemetry.dashboard.ingest.window=30s",
			"pulsemetry.dashboard.ingest.window=90s",
			"pulsemetry.dashboard.ingest.window=0m",
			"pulsemetry.dashboard.ingest.delayed-after=0s",
			"pulsemetry.dashboard.ingest.delayed-after=-1m",
			// 중단 기준은 지연 기준보다 길어야 두 상태가 갈린다.
			"pulsemetry.dashboard.ingest.down-after=5m",
			"pulsemetry.dashboard.ingest.down-after=1m",
		],
	)
	@DisplayName("수집 상태 판정의 임계값이 서로 맞지 않으면 기동이 실패한다")
	fun ingestThresholdsMustBeConsistent(override: String) {
		runner.withPropertyValues(*complete, override).run { assertThat(it).hasFailed() }
	}

	@Test
	@DisplayName("수집 상태 판정의 임계값은 준 값 그대로 뜬다")
	fun ingestThresholdsBind() {
		runner.withPropertyValues(*complete).run {
			assertThat(it).hasNotFailed()
			val ingest = it.getBean(DashboardApiProperties::class.java).ingest
			assertThat(ingest.window).isEqualTo(Duration.ofMinutes(15))
			assertThat(ingest.delayedAfter).isEqualTo(Duration.ofMinutes(5))
			assertThat(ingest.downAfter).isEqualTo(Duration.ofHours(3))
		}
	}

	@ParameterizedTest
	@ValueSource(strings = ["0", "10", "90", ""])
	@DisplayName("유휴 기준 일수는 요청서의 네 값 중 하나여야 한다")
	fun idleDaysMustBeOneOfTheContractValues(value: String) {
		runner.withPropertyValues(*complete, "pulsemetry.dashboard.members.idle-days=$value").run { assertThat(it).hasFailed() }
	}

	@ParameterizedTest
	@ValueSource(strings = ["dashboard-cache", "cache; DROP", "1cache", "dashboard_cache.x"])
	@DisplayName("DB 이름이 식별자 형식이 아니면 기동이 실패한다 — 복사 SQL 에 식별자로 들어간다")
	fun databaseMustBeIdentifier(value: String) {
		runner.withPropertyValues(*complete, "pulsemetry.dashboard.clickhouse.cache.database=$value").run { assertThat(it).hasFailed() }
		runner.withPropertyValues(*complete, "pulsemetry.dashboard.clickhouse.source.database=$value").run { assertThat(it).hasFailed() }
	}
}
