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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant

/**
 * 조직별 보존 삭제(ADR 0020 §8 · ADR 0024 §4 의 4·5)를 실제 ClickHouse 위에서 본다. 기대값은 그 규칙에서 온다 — tenant 와
 * `source_time < 경계` 로만 지우고, 한 관측의 모든 revision 이 함께 지워지며, 같은 월 파티션의 다른 tenant 와 수신 ledger 는 남는다.
 *
 * merge 가 revision 을 합치면 "관측 수와 행 수가 다르다"를 볼 수 없으므로 시드 동안 merge 를 멈춘다. lightweight DELETE 는 mutation 이라
 * merge 가 멈춰 있으면 끝나지 않는다 — 지우는 테스트는 지우기 전에 merge 를 다시 켠다.
 */
@Testcontainers
class RetentionPurgeTest {

	private lateinit var client: ClickHouseHttpClient
	private lateinit var purge: RetentionPurge
	private lateinit var events: TelemetryEventsSink
	private lateinit var points: TelemetryMetricPointsSink

	/** 2025-09-24 KST 00:00 — 사례 8 의 경계. */
	private val boundary = Instant.parse("2025-09-23T15:00:00Z")
	private val other = "99999999-9999-9999-9999-999999999999"

	private val first = RowVersioning.live(Instant.parse("2026-01-01T00:00:00Z"))
	private val second = RowVersioning.live(Instant.parse("2026-02-01T00:00:00Z"))

	@BeforeEach
	fun setUp() {
		client = ClickHouseHttpClient(url())
		ClickHouseSchemaMigrator(client).apply()
		for (table in listOf(TelemetryEventsSink.TABLE, TelemetryMetricPointsSink.TABLE, IngestLedgerSink.TABLE)) {
			client.execute("TRUNCATE TABLE $table")
			client.execute("SYSTEM STOP MERGES $table")
		}
		purge = RetentionPurge(client)
		events = TelemetryEventsSink(client)
		points = TelemetryMetricPointsSink(client)
	}

	@AfterEach
	fun startMerges() {
		resumeMerges()
	}

	private fun resumeMerges() {
		for (table in listOf(TelemetryEventsSink.TABLE, TelemetryMetricPointsSink.TABLE, IngestLedgerSink.TABLE)) {
			client.execute("SYSTEM START MERGES $table")
		}
	}

	private fun eventAt(id: Char, time: Instant, tenant: String = TENANT): EnrichedEvent {
		val base = event().observation
		return event(base.copy(envelope = base.envelope.copy(observationId = hex(id), sourceTime = EpochNanos.of(time), tenantId = tenant)))
	}

	private fun pointAt(id: Char, time: Instant): EnrichedMetricPoint {
		val base = metricPointObservation(hex(id))
		return metricPoint(base.copy(envelope = base.envelope.copy(sourceTime = EpochNanos.of(time))))
	}

	private fun seed() {
		val open = AnalysisWriteBoundary(TENANT, null, 0)
		// 경계 이전 관측 1 은 revision 둘, 관측 2 는 하나. 관측 3 은 경계 시각 — 남는다.
		events.insert(listOf(eventAt('1', boundary.minusSeconds(86_400)), eventAt('2', boundary.minusNanos(1)), eventAt('3', boundary)), first, open)
		events.insert(listOf(eventAt('1', boundary.minusSeconds(86_400))), second, open)
		points.insert(listOf(pointAt('4', boundary.minusSeconds(60)), pointAt('5', boundary.plusSeconds(60))), first, open)
		// 같은 월 파티션(2025-09)의 다른 조직.
		events.insert(listOf(eventAt('6', boundary.minusSeconds(3_600), tenant = other)), first, AnalysisWriteBoundary(other, null, 0))
		client.execute(
			"INSERT INTO ${IngestLedgerSink.TABLE} (tenant_id, installation_id, received_time, receipt_id, signal, product, record_count, rejected_count, masking_version) " +
				"VALUES ('$TENANT', 'i', '2025-09-01 00:00:00', 'r1', 'log', 'claude_code', 1, 0, 'm')",
		)
	}

	@Test
	@DisplayName("삭제 대상은 관측 고유 수와 물리 revision 행 수를 따로 센다 — 테이블마다, 이 tenant 의 경계 이전만")
	fun countsSeparateObservationsFromRevisionRows() {
		seed()

		val targets = purge.count(TENANT, boundary)

		assertThat(targets.events).isEqualTo(RetentionPurge.Target(observations = 2, rows = 3))
		assertThat(targets.metricPoints).isEqualTo(RetentionPurge.Target(observations = 1, rows = 1))
	}

	@Test
	@DisplayName("사례 8 — tenant 와 경계로 모든 revision 을 지우고, 경계 시각의 관측·같은 파티션의 다른 조직·수신 ledger 는 남는다")
	fun deletesEveryRevisionBeforeTheBoundaryOnlyForTheTenant() {
		seed()
		resumeMerges()

		purge.delete(TENANT, boundary)

		assertThat(purge.remaining(TENANT, boundary)).isZero()
		assertThat(ids(TelemetryEventsSink.TABLE, TENANT)).containsExactly(hex('3'))
		assertThat(ids(TelemetryMetricPointsSink.TABLE, TENANT)).containsExactly(hex('5'))
		assertThat(ids(TelemetryEventsSink.TABLE, other)).containsExactly(hex('6'))
		assertThat(client.execute("SELECT count() FROM ${IngestLedgerSink.TABLE} WHERE tenant_id = '$TENANT' FORMAT TSV").trim()).isEqualTo("1")
	}

	@Test
	@DisplayName("다시 지워도 같다 — 지울 것이 없으면 아무것도 바꾸지 않는다")
	fun deletingAgainIsHarmless() {
		seed()
		resumeMerges()
		purge.delete(TENANT, boundary)

		purge.delete(TENANT, boundary)

		assertThat(purge.count(TENANT, boundary)).isEqualTo(RetentionPurge.Targets(RetentionPurge.Target(0, 0), RetentionPurge.Target(0, 0)))
		assertThat(ids(TelemetryEventsSink.TABLE, TENANT)).containsExactly(hex('3'))
	}

	@Test
	@DisplayName("tenant 없이 지우지 않는다")
	fun blankTenantIsRejected() {
		assertThatThrownBy { purge.delete(" ", boundary) }.isInstanceOf(IllegalArgumentException::class.java)
	}

	/** 물리 행의 관측 ID(FINAL 없이 — revision 이 남았는지 본다). */
	private fun ids(table: String, tenant: String): List<String> =
		client.execute("SELECT DISTINCT observation_id FROM $table WHERE tenant_id = '$tenant' ORDER BY observation_id FORMAT TSV")
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
