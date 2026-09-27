package com.team376.pulsemetry.persistence.telemetry

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/** 백필 원천(ADR 0021 §2) — 구 `enriched_events` 의 서로 다른 tenant. 빈 값만 뺀다. */
@Testcontainers
class PreLedgerHistorySourceTest {

	private lateinit var client: ClickHouseHttpClient

	@BeforeEach
	fun setUp() {
		client = ClickHouseHttpClient("http://${clickhouse.host}:${clickhouse.getMappedPort(HTTP_PORT)}")
		ClickHouseSchemaMigrator(client).apply()
		client.execute("TRUNCATE TABLE IF EXISTS enriched_events")
	}

	@Test
	@DisplayName("행이 있는 tenant 를 한 번씩 돌려준다 — 빈 tenant 는 빼고 UUID 가 아닌 값은 거르지 않는다")
	fun distinctTenants() {
		val rows = listOf("e1" to TENANT_B, "e2" to TENANT_A, "e3" to TENANT_A, "e4" to "", "e5" to "(unknown)")
		client.execute(
			"INSERT INTO enriched_events FORMAT JSONEachRow",
			rows.joinToString("\n") { (id, tenant) ->
				"""{"event_id":"$id","ts":"2025-12-01 00:00:00","tenant_id":"$tenant","installation_id":"i","signal":"logs",""" +
					""""product":"codex","team_ids_as_of":[],"raw_json":"{}","enrichment_json":"{}"}"""
			}.toByteArray(),
		)

		val source = PreLedgerHistorySource(client)

		assertThat(source.tenantIds()).containsExactly("(unknown)", TENANT_A, TENANT_B)
		assertThat(source.source).isEqualTo("enriched_events")
	}

	@Test
	@DisplayName("원천이 비면 빈 목록이다")
	fun emptySource() {
		assertThat(PreLedgerHistorySource(client).tenantIds()).isEmpty()
	}

	companion object {
		private const val HTTP_PORT: Int = 8123
		private const val TENANT_A: String = "11111111-1111-1111-1111-111111111111"
		private const val TENANT_B: String = "22222222-2222-2222-2222-222222222222"

		@Container
		@JvmStatic
		val clickhouse: GenericContainer<*> =
			GenericContainer("clickhouse/clickhouse-server:24.8-alpine")
				.withExposedPorts(HTTP_PORT)
				// 이게 없으면 entrypoint 가 default 유저를 루프백 전용으로 잠근다 (infra ADR-0019).
				.withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1")
				.waitingFor(Wait.forHttp("/ping").forPort(HTTP_PORT).forStatusCode(200))
	}
}
