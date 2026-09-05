package com.team376.pulsemetry.telemetry.support

import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait

/**
 * 조립 앱의 통합 테스트 기반. **모든 애너테이션이 여기 있다.**
 *
 * 하위 클래스가 `@Import` 나 `@SpringBootTest(properties = …)` 를 더하면 컨텍스트 캐시 키가
 * 갈려 Postgres 컨테이너가 하나 더 뜬다. `@DynamicPropertySource` 도 마찬가지라 이 클래스에만
 * 둔다 — 캐시 키가 메서드 집합으로 계산되므로, 하위가 각자 선언하면 컨텍스트가 쪼개진다.
 *
 * ClickHouse 는 Spring 이 관리하지 않는다. `@ServiceConnection` 이 없는 저장소라
 * 정적 컨테이너를 직접 띄우고 URL 만 프로퍼티로 넘긴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresContainerConfig::class, IngestTestData::class)
abstract class AbstractIngestIntegrationTest {

	companion object {
		private const val HTTP_PORT: Int = 8123

		/** 태그를 infra 의 배포 이미지와 `EnrichedEventsSinkTest` 에 맞춘다. */
		@JvmStatic
		val clickhouse: GenericContainer<*> =
			GenericContainer("clickhouse/clickhouse-server:24.8-alpine")
				.withExposedPorts(HTTP_PORT)
				// 이게 없으면 entrypoint 가 default 유저를 루프백 전용으로 잠근다 (infra ADR-0019).
				.withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1")
				.waitingFor(Wait.forHttp("/ping").forPort(HTTP_PORT).forStatusCode(200))
				.also { it.start() }

		@JvmStatic
		@DynamicPropertySource
		fun clickHouseProperties(registry: DynamicPropertyRegistry) {
			registry.add("pulsemetry.telemetry.clickhouse.url") {
				"http://${clickhouse.host}:${clickhouse.getMappedPort(HTTP_PORT)}"
			}
		}

		fun clickHouseUrl(): String = "http://${clickhouse.host}:${clickhouse.getMappedPort(HTTP_PORT)}"
	}
}
