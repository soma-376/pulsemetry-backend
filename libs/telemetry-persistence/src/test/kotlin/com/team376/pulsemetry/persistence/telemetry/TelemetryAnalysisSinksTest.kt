package com.team376.pulsemetry.persistence.telemetry

import com.team376.pulsemetry.persistence.telemetry.AnalysisSamples.event
import com.team376.pulsemetry.persistence.telemetry.AnalysisSamples.hex
import com.team376.pulsemetry.persistence.telemetry.AnalysisSamples.metricPoint
import com.team376.pulsemetry.persistence.telemetry.AnalysisSamples.metricPointObservation
import com.team376.pulsemetry.telemetry.adapter.observation.EventObservation
import com.team376.pulsemetry.telemetry.adapter.observation.EventType
import com.team376.pulsemetry.telemetry.adapter.observation.ReportedCostBasis
import com.team376.pulsemetry.telemetry.adapter.observation.RowVersioning
import com.team376.pulsemetry.telemetry.enricher.observation.EnrichedEvent
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.Instant

/**
 * 두 분석 sink 의 교체 저장(ADR 0020 §3)을 실제 ClickHouse 위에서 본다. live 수신의 `row_version` 은
 * `(normalizer_rev << 32) | receipt 수신 epoch 초` 이고, 관측마다 가장 큰 버전의 행이 `FINAL` 에 남는다.
 */
@Testcontainers
class TelemetryAnalysisSinksTest {

	private lateinit var client: ClickHouseHttpClient
	private lateinit var events: TelemetryEventsSink
	private lateinit var points: TelemetryMetricPointsSink

	private val first = RowVersioning.live(Instant.parse("2026-01-01T00:00:05Z"))
	private val later = RowVersioning.live(Instant.parse("2026-01-01T00:10:00Z"))

	/** 삭제 경계가 없는 tenant — 이 테스트는 교체 저장만 본다(경계는 `AnalysisWriteBoundaryTest`). */
	private val open = AnalysisWriteBoundary(AnalysisSamples.TENANT, deletedBefore = null, policyEpoch = 0)

	@BeforeEach
	fun setUp() {
		client = ClickHouseHttpClient(url())
		ClickHouseSchemaMigrator(client).apply()
		client.execute("TRUNCATE TABLE IF EXISTS ${TelemetryEventsSink.TABLE}")
		client.execute("TRUNCATE TABLE IF EXISTS ${TelemetryMetricPointsSink.TABLE}")
		events = TelemetryEventsSink(client)
		points = TelemetryMetricPointsSink(client)
	}

	private fun usage(id: Char, tokens: Long?, cost: BigDecimal? = null): EventObservation {
		val base = event().observation
		return base.copy(
			envelope = base.envelope.copy(observationId = hex(id)),
			eventType = EventType.MODEL_RESPONSE_USAGE,
			tokensInput = tokens,
			costReportedUsd = cost,
			reportedCostBasis = if (cost == null) ReportedCostBasis.UNKNOWN else ReportedCostBasis.ESTIMATE,
		)
	}

	@Test
	@DisplayName("두 sink 가 행 수를 돌려주고 두 테이블에 쓴다 — 빈 배치는 요청을 보내지 않는다")
	fun insertsIntoBothTables() {
		assertThat(events.insert(listOf(event(usage('1', 10)), event(usage('2', 20))), first, open)).isEqualTo(2)
		assertThat(points.insert(listOf(metricPoint()), first, open)).isEqualTo(1)
		assertThat(events.insert(emptyList(), first, open)).isZero()
		assertThat(TelemetryEventsSink(ClickHouseHttpClient("http://127.0.0.1:1")).insert(emptyList(), first, open)).isZero()

		assertThat(query("SELECT count() FROM ${TelemetryEventsSink.TABLE} FINAL")).isEqualTo("2")
		assertThat(query("SELECT count() FROM ${TelemetryMetricPointsSink.TABLE} FINAL")).isEqualTo("1")
		assertThat(query("SELECT DISTINCT toString(row_version) FROM ${TelemetryEventsSink.TABLE}"))
			.isEqualTo(((1uL shl 32) or 1_767_225_605uL).toString())
	}

	@Test
	@DisplayName("같은 배치를 다시 적재하면 한 행으로 수렴한다 — 재시도는 멱등이다")
	fun retryingTheSameBatchConverges() {
		val batch = listOf(event(usage('1', 10)), event(usage('2', 20)))
		events.insert(batch, first, open)
		events.insert(batch, first, open)

		assertThat(query("SELECT count() FROM ${TelemetryEventsSink.TABLE} FINAL")).isEqualTo("2")
		assertThat(query("SELECT groupArray(tokens_input) FROM (SELECT tokens_input FROM ${TelemetryEventsSink.TABLE} FINAL ORDER BY observation_id)"))
			.isEqualTo("[10,20]")
	}

	@Test
	@DisplayName("소속 편집 뒤 재처리 — 나중 receipt 의 행(새 귀속)이 이기고 analysis_hash 는 같다")
	fun laterReceiptWins() {
		events.insert(listOf(event(usage('1', 10), AnalysisSamples.org(teamIds = listOf("team-a")))), first, open)
		events.insert(listOf(event(usage('1', 10), AnalysisSamples.org(teamIds = listOf("team-b")))), later, open)

		assertThat(query("SELECT team_id_as_of, analysis_hash FROM ${TelemetryEventsSink.TABLE} FINAL FORMAT TSV"))
			.isEqualTo("team-b\t${hex('b')}")
	}

	@Test
	@DisplayName("높은 normalizer_rev 가 언제나 이긴다 — 구 규칙의 결과가 늦게 와도 되돌리지 못하고 null 정정도 유지된다")
	fun higherRevisionAlwaysWins() {
		events.insert(listOf(event(usage('1', 10, BigDecimal("0.5")))), RowVersioning.live(Instant.parse("2026-01-01T00:00:05Z"), normalizerRev = 2u), open)
		events.insert(listOf(event(usage('1', 99, BigDecimal("9.9")))), RowVersioning.live(Instant.parse("2026-06-01T00:00:00Z"), normalizerRev = 1u), open)

		assertThat(query("SELECT tokens_input, toString(cost_reported_usd), normalizer_rev FROM ${TelemetryEventsSink.TABLE} FINAL FORMAT TSV"))
			.isEqualTo("10\t0.5\t2")

		events.insert(listOf(event(usage('1', 10, null))), RowVersioning.live(Instant.parse("2026-01-02T00:00:00Z"), normalizerRev = 2u), open)
		assertThat(query("SELECT toString(cost_reported_usd) FROM ${TelemetryEventsSink.TABLE} FINAL")).isEqualTo("\\N")
	}

	@Test
	@DisplayName("행 하나라도 컬럼 타입에 들어가지 않으면 요청 전에 거부한다 — 배치의 어느 행도 쓰이지 않는다")
	fun oneInvalidRowRejectsTheBatchBeforeSending() {
		val batch = listOf(event(usage('1', 10)), event(usage('2', 20, BigDecimal("0.0000000000001"))))

		assertThatThrownBy { events.insert(batch, first, open) }.isInstanceOf(TelemetrySinkRejectedException::class.java)
		assertThat(query("SELECT count() FROM ${TelemetryEventsSink.TABLE}")).isEqualTo("0")
	}

	@Test
	@DisplayName("ClickHouse 응답의 분류는 클라이언트 그대로다 — 4xx 영구, 연결 실패 일시")
	fun clickHouseFailuresKeepTheirClassification() {
		val batch = listOf<EnrichedEvent>(event())

		assertThatThrownBy { TelemetryEventsSink(ClickHouseHttpClient(url(), database = "missing_database")).insert(batch, first, open) }
			.isInstanceOf(TelemetrySinkRejectedException::class.java)
		assertThatThrownBy { TelemetryMetricPointsSink(ClickHouseHttpClient("http://127.0.0.1:1")).insert(listOf(metricPoint(metricPointObservation())), first, open) }
			.isInstanceOf(TelemetrySinkUnavailableException::class.java)
	}

	private fun url(): String = "http://${clickhouse.host}:${clickhouse.getMappedPort(HTTP_PORT)}"

	private fun query(sql: String): String = client.execute(sql).trim()

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
