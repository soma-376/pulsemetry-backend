package com.team376.pulsemetry.telemetry.support

import com.team376.pulsemetry.persistence.enrollment.repository.InstallationRepository
import com.team376.pulsemetry.persistence.enrollment.repository.TelemetryTokenRepository
import com.team376.pulsemetry.persistence.enrollment.support.PostgresContainerConfig
import com.team376.pulsemetry.persistence.telemetry.TelemetryEventsSink
import com.team376.pulsemetry.persistence.telemetryops.TenantIngestSummaryStore
import com.team376.pulsemetry.persistence.telemetryops.TenantRetentionBoundaryStore
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
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
 *
 * spy 들도 같은 이유로 여기 있다 — 빈 오버라이드는 캐시 키의 일부다. 장애를 흉내 내는 테스트만 쓰고(인증 조회·
 * 보강 조회·분석 적재·요약 쓰기·삭제 경계 조회), 나머지는 실물 그대로 지나간다(테스트마다 원복된다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresContainerConfig::class, IngestTestData::class)
abstract class AbstractIngestIntegrationTest {

	@MockitoSpyBean
	protected lateinit var telemetryTokens: TelemetryTokenRepository

	/** 보강의 RDS 장애를 흉내 낸다. */
	@MockitoSpyBean
	protected lateinit var installations: InstallationRepository

	/** 분석 테이블 적재의 ClickHouse 장애를 흉내 낸다. */
	@MockitoSpyBean
	protected lateinit var telemetryEvents: TelemetryEventsSink

	/** 수집 운영 기록(요약)의 RDS 장애를 흉내 낸다. */
	@MockitoSpyBean
	protected lateinit var summaries: TenantIngestSummaryStore

	/** 삭제 경계 조회의 RDS 장애를 흉내 낸다. 경계를 발효하는 테스트는 실물 그대로 쓴다. */
	@MockitoSpyBean
	protected lateinit var retentionBoundaries: TenantRetentionBoundaryStore

	companion object {
		private const val HTTP_PORT: Int = 8123

		/** 태그를 infra 의 배포 이미지와 `TelemetryAnalysisTablesTest` 에 맞춘다. */
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
