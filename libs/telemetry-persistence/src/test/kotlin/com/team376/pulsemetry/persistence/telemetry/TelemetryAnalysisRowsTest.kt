package com.team376.pulsemetry.persistence.telemetry

import com.team376.pulsemetry.persistence.telemetry.AnalysisSamples.event
import com.team376.pulsemetry.telemetry.adapter.observation.EpochNanos
import com.team376.pulsemetry.telemetry.adapter.observation.EventType
import com.team376.pulsemetry.telemetry.adapter.observation.QualityFlag
import com.team376.pulsemetry.telemetry.adapter.observation.ReportedCostBasis
import com.team376.pulsemetry.telemetry.adapter.observation.RowVersioning
import com.team376.pulsemetry.telemetry.adapter.observation.UsageRole
import com.team376.pulsemetry.telemetry.adapter.observation.UsageScope
import com.team376.pulsemetry.telemetry.adapter.observation.withFlags
import com.team376.pulsemetry.telemetry.enricher.observation.EnrichedEvent
import org.assertj.core.api.Assertions.assertThat
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
 * 인코더가 믿는 스키마([AnalysisColumns])가 실제 DDL 과 같고, 인코딩한 행이 **값 그대로** 저장되는지 실제 ClickHouse 위에서
 * 본다(ADR 0020 §1). 저장 값은 ClickHouse 의 `toString` 으로 읽는다 — 정수·Decimal·시각이 표기 변환 없이 비교된다.
 *
 * 컨테이너 구성은 [TelemetryAnalysisTablesTest] 와 같다.
 */
@Testcontainers
class TelemetryAnalysisRowsTest {

	private lateinit var client: ClickHouseHttpClient

	private val versioning = RowVersioning.of(1u, 1_767_225_605u)

	@BeforeEach
	fun setUp() {
		client = ClickHouseHttpClient("http://${clickhouse.host}:${clickhouse.getMappedPort(HTTP_PORT)}")
		ClickHouseSchemaMigrator(client).apply()
		client.execute("TRUNCATE TABLE IF EXISTS $EVENTS")
		client.execute("TRUNCATE TABLE IF EXISTS $METRICS")
	}

	@Test
	@DisplayName("인코더의 컬럼 선언이 두 테이블의 system.columns 와 이름·타입·순서까지 같다")
	fun declarationsMatchTheTables() {
		assertThat(AnalysisColumns.EVENTS.map { it.name to it.type }).containsExactlyElementsOf(columns(EVENTS))
		assertThat(AnalysisColumns.METRIC_POINTS.map { it.name to it.type }).containsExactlyElementsOf(columns(METRICS))
	}

	@Test
	@DisplayName("이벤트 행이 값 그대로 저장된다 — 2^53+1·Decimal 경계·나노초·배열·Map·Bool·UInt")
	fun eventValuesRoundTrip() {
		val base = event().observation
		val observation = base.copy(
			envelope = base.envelope.copy(usageRole = UsageRole.PRIMARY, usageScope = UsageScope.RESPONSE, attrs = mapOf("model" to "m-1")),
			eventType = EventType.MODEL_RESPONSE_USAGE,
			endTime = EpochNanos.of(Instant.parse("1969-12-31T23:59:59.999999999Z")),
			durationNs = Long.MAX_VALUE,
			tokensInput = 9_007_199_254_740_993L,
			tokensOutput = 0,
			costReportedUsd = BigDecimal("99999999999999999999999999.999999999999"),
			costEstimatedUsd = BigDecimal("0.000000000001"),
			pricingVersion = "p1",
			reportedCostBasis = ReportedCostBasis.ESTIMATE,
			success = false,
			httpStatus = 65_535,
			severityNumber = 255,
		).withFlags(QualityFlag.COST_CONFLICT, QualityFlag.MULTI_TEAM_MEMBERSHIP)
		insert(event(observation, AnalysisSamples.org(teamIds = listOf("t1", "t2"))))

		val stored = select(
			EVENTS,
			"toString(tokens_input)", "toString(tokens_output)", "toString(duration_ns)", "toString(cost_reported_usd)",
			"toString(cost_estimated_usd)", "toString(source_time)", "toString(end_time)", "toString(received_time)",
			"toString(quality_flags)", "toString(attrs)", "toString(team_ids_as_of)", "toString(team_id_as_of)", "toString(success)",
			"toString(http_status)", "toString(severity_number)", "toString(row_version)", "toString(normalizer_rev)",
			"event_type", "usage_role", "member_id", "enrichment_json",
		)

		assertThat(stored).containsExactly(
			"9007199254740993", "0", "9223372036854775807", "99999999999999999999999999.999999999999",
			"0.000000000001", "2026-01-01 00:00:00.123456789", "1969-12-31 23:59:59.999999999", "2026-01-01 00:00:05.000000000",
			"['cost_conflict','multi_team_membership']", "{'model':'m-1'}", "['t1','t2']", NULL, "false",
			"65535", "255", ((1uL shl 32) or 1_767_225_605uL).toString(), "1",
			"model.response.usage", "primary", AnalysisSamples.MEMBER, AnalysisSamples.org().enrichmentJson,
		)
	}

	@Test
	@DisplayName("미보고 이벤트의 Nullable 컬럼은 전부 NULL 이다 — 0 이나 빈 문자열이 아니다")
	fun unreportedEventColumnsAreNull() {
		insert(event(org = AnalysisSamples.org(memberId = null)))

		val nullable = AnalysisColumns.EVENTS.filter { it.nullable }.map { it.name }
		assertThat(select(EVENTS, *nullable.map { "isNull($it)" }.toTypedArray())).containsOnly("1").hasSize(nullable.size)
	}

	private fun insert(vararg events: EnrichedEvent) {
		val body = events.joinToString("\n", postfix = "\n") { TelemetryEventRow.toJson(it, versioning) }
		client.execute(AnalysisInsert.query(EVENTS, AnalysisColumns.EVENTS), body.toByteArray())
	}

	/** 한 행의 식을 탭으로 나눈 원문. */
	private fun select(table: String, vararg expressions: String): List<String> =
		client.execute("SELECT ${expressions.joinToString(", ")} FROM $table FINAL FORMAT TSVRaw").trimEnd('\n').split('\t')

	private fun columns(table: String): List<Pair<String, String>> =
		client.execute(
			"SELECT name, type FROM system.columns WHERE database = currentDatabase() AND table = '$table' " +
				"ORDER BY position FORMAT TSVRaw",
		).trim().lines().map { line -> line.split('\t').let { it[0] to it[1] } }

	companion object {
		private const val HTTP_PORT: Int = 8123
		private const val EVENTS: String = "telemetry_events"
		private const val METRICS: String = "telemetry_metric_points"
		private const val NULL: String = "\\N"

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
