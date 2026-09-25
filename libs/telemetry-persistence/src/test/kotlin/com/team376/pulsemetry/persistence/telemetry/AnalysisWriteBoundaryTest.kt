package com.team376.pulsemetry.persistence.telemetry

import com.team376.pulsemetry.persistence.telemetry.AnalysisSamples.TENANT
import com.team376.pulsemetry.persistence.telemetry.AnalysisSamples.event
import com.team376.pulsemetry.persistence.telemetry.AnalysisSamples.hex
import com.team376.pulsemetry.persistence.telemetry.AnalysisSamples.metricPoint
import com.team376.pulsemetry.persistence.telemetry.AnalysisSamples.metricPointObservation
import com.team376.pulsemetry.telemetry.adapter.observation.EpochNanos
import com.team376.pulsemetry.telemetry.adapter.observation.RowVersioning
import com.team376.pulsemetry.telemetry.enricher.observation.EnrichedEvent
import com.team376.pulsemetry.telemetry.enricher.observation.EnrichedMetricPoint
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant

/**
 * 분석 sink 의 삭제 경계 집행(ADR 0024 §2·§3)을 실제 ClickHouse 위에서 본다. 기대값은 그 규칙에서 온다 — `source_time` 이 경계보다
 * 이른 관측은 쓰지 않고 경계 시각의 관측은 쓴다. 쓰는 쪽이 넘긴 경계와 서버의 fence 중 늦은 쪽이 이긴다.
 */
@Testcontainers
class AnalysisWriteBoundaryTest {

	private lateinit var client: ClickHouseHttpClient
	private lateinit var events: TelemetryEventsSink
	private lateinit var points: TelemetryMetricPointsSink
	private lateinit var fence: RetentionFence

	private val versioning = RowVersioning.live(Instant.parse("2026-09-24T00:00:05Z"))

	/** 2025-09-24 KST 00:00 — 사례 8 의 경계. */
	private val boundary = Instant.parse("2025-09-23T15:00:00Z")

	private val open = AnalysisWriteBoundary(TENANT, deletedBefore = null, policyEpoch = 0)

	@BeforeEach
	fun setUp() {
		client = ClickHouseHttpClient(url())
		ClickHouseSchemaMigrator(client).apply()
		for (table in listOf(TelemetryEventsSink.TABLE, TelemetryMetricPointsSink.TABLE, RetentionFence.TABLE)) {
			client.execute("TRUNCATE TABLE IF EXISTS $table")
		}
		events = TelemetryEventsSink(client)
		points = TelemetryMetricPointsSink(client)
		fence = RetentionFence(client)
	}

	private fun eventAt(id: Char, time: Instant, tenant: String = TENANT): EnrichedEvent {
		val base = event().observation
		return event(base.copy(envelope = base.envelope.copy(observationId = hex(id), sourceTime = EpochNanos.of(time), tenantId = tenant)))
	}

	private fun pointAt(id: Char, time: Instant): EnrichedMetricPoint {
		val base = metricPointObservation(hex(id))
		return metricPoint(base.copy(envelope = base.envelope.copy(sourceTime = EpochNanos.of(time))))
	}

	@Test
	@DisplayName("경계 이전 관측은 INSERT 에 싣지 않는다 — 경계 시각의 관측은 남고, 돌려주는 수는 실은 행 수다")
	fun sinkDropsObservationsBeforeTheBoundary() {
		val guarded = AnalysisWriteBoundary(TENANT, boundary, policyEpoch = 1)

		val sentEvents = events.insert(
			listOf(eventAt('1', boundary.minusNanos(1)), eventAt('2', boundary), eventAt('3', boundary.plusSeconds(60))),
			versioning,
			guarded,
		)
		val sentPoints = points.insert(listOf(pointAt('4', boundary.minusSeconds(1)), pointAt('5', boundary)), versioning, guarded)

		assertThat(sentEvents).isEqualTo(2)
		assertThat(sentPoints).isEqualTo(1)
		assertThat(ids(TelemetryEventsSink.TABLE)).containsExactly(hex('2'), hex('3'))
		assertThat(ids(TelemetryMetricPointsSink.TABLE)).containsExactly(hex('5'))
	}

	@Test
	@DisplayName("모두 경계 이전이면 요청을 보내지 않는다")
	fun allBeforeTheBoundarySendsNothing() {
		val unreachable = TelemetryEventsSink(ClickHouseHttpClient("http://127.0.0.1:1"))

		assertThat(unreachable.insert(listOf(eventAt('1', boundary.minusSeconds(1))), versioning, AnalysisWriteBoundary(TENANT, boundary, 1)))
			.isZero()
	}

	@Test
	@DisplayName("넘긴 경계가 낡아도 fence 이전 행은 서버가 쓰지 않는다 — 두 분석 테이블 모두")
	fun serverFenceWinsOverAStaleBoundary() {
		fence.write(TENANT, RetentionFence.Value(boundary, policyEpoch = 1))

		// 쓰는 쪽은 fence 가 움직이기 전의 경계(없음)를 읽었다.
		events.insert(listOf(eventAt('1', boundary.minusSeconds(1)), eventAt('2', boundary)), versioning, open)
		points.insert(listOf(pointAt('3', boundary.minusSeconds(1)), pointAt('4', boundary.plusSeconds(1))), versioning, open)

		assertThat(ids(TelemetryEventsSink.TABLE)).containsExactly(hex('2'))
		assertThat(ids(TelemetryMetricPointsSink.TABLE)).containsExactly(hex('4'))
	}

	@Test
	@DisplayName("fence 가 없으면 아무것도 버리지 않는다 — 1970 년 이전 source_time 도 남는다")
	fun noFenceKeepsEverything() {
		events.insert(listOf(eventAt('1', Instant.parse("1950-01-01T00:00:00Z")), eventAt('2', boundary)), versioning, open)

		assertThat(ids(TelemetryEventsSink.TABLE)).containsExactly(hex('1'), hex('2'))
	}

	@Test
	@DisplayName("fence 는 tenant 마다 따로다 — 다른 조직의 fence 가 이 조직의 행을 버리지 않는다")
	fun fenceIsPerTenant() {
		fence.write("99999999-9999-9999-9999-999999999999", RetentionFence.Value(boundary, policyEpoch = 1))

		events.insert(listOf(eventAt('1', boundary.minusSeconds(1))), versioning, open)

		assertThat(ids(TelemetryEventsSink.TABLE)).containsExactly(hex('1'))
	}

	@Test
	@DisplayName("경계의 tenant 와 다른 행이 섞이면 요청을 보내기 전에 실패한다 — 조용히 버리지 않는다")
	fun foreignTenantRowFailsBeforeTheRequest() {
		val unreachable = TelemetryEventsSink(ClickHouseHttpClient("http://127.0.0.1:1"))
		val batch = listOf(eventAt('1', boundary), eventAt('2', boundary, tenant = "99999999-9999-9999-9999-999999999999"))

		assertThatThrownBy { unreachable.insert(batch, versioning, open) }.isInstanceOf(IllegalArgumentException::class.java)
	}

	@Test
	@DisplayName("INSERT 의 query_id 가 tenant 와 쓰는 쪽이 읽은 epoch 를 싣는다")
	fun queryIdCarriesTenantAndEpoch() {
		events.insert(listOf(eventAt('1', boundary)), versioning, AnalysisWriteBoundary(TENANT, boundary, policyEpoch = 3))

		client.execute("SYSTEM FLUSH LOGS")
		val finished = client.execute(
			"SELECT count() FROM system.query_log WHERE type = 'QueryFinish' AND query_kind = 'Insert' " +
				"AND startsWith(query_id, {prefix:String}) FORMAT TSV",
			params = mapOf("prefix" to "${AnalysisWriteBoundary.QUERY_ID_PREFIX}$TENANT:3:"),
		).trim()
		assertThat(finished).isEqualTo("1")
	}

	@Test
	@DisplayName("fence 는 가장 늦은 값이다 — 이른 값을 다시 써도 내려가지 않고, 없으면 null")
	fun fenceCurrentIsTheMaximum() {
		assertThat(fence.current(TENANT)).isNull()

		val later = RetentionFence.Value(boundary.plusSeconds(86_400), policyEpoch = 2)
		fence.write(TENANT, RetentionFence.Value(boundary, policyEpoch = 1))
		fence.write(TENANT, later)
		fence.write(TENANT, RetentionFence.Value(boundary, policyEpoch = 1))

		assertThat(fence.current(TENANT)).isEqualTo(later)
	}

	private fun ids(table: String): List<String> =
		client.execute("SELECT observation_id FROM $table FINAL WHERE tenant_id = '$TENANT' ORDER BY observation_id FORMAT TSV")
			.lines().filter { it.isNotBlank() }

	private fun url(): String = "http://${clickhouse.host}:${clickhouse.getMappedPort(HTTP_PORT)}"

	companion object {
		private const val HTTP_PORT: Int = 8123

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
