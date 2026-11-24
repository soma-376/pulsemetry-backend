package com.team376.pulsemetry.persistence.telemetry

import com.team376.pulsemetry.persistence.telemetry.AnalysisSamples.envelope
import com.team376.pulsemetry.persistence.telemetry.AnalysisSamples.event
import com.team376.pulsemetry.persistence.telemetry.AnalysisSamples.hex
import com.team376.pulsemetry.persistence.telemetry.AnalysisSamples.metricPoint
import com.team376.pulsemetry.persistence.telemetry.AnalysisSamples.metricPointObservation
import com.team376.pulsemetry.telemetry.model.EpochNanos
import com.team376.pulsemetry.telemetry.model.EventObservation
import com.team376.pulsemetry.telemetry.model.EventType
import com.team376.pulsemetry.telemetry.model.RowVersioning
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.stream.Stream

/**
 * Kafka 배치의 이분할 허용 목록([ClickHouseErrorCodes.INPUT_DEFECT_CODES])의 근거를 실제 ClickHouse 위에서 고정한다(PROJ-341 · 11차 개정안 2절).
 *
 * 프로젝트의 실제 분석 INSERT 로 **두 달에 걸친 블록**(10월 31일·11월 1일)을 보내고 마지막 행에만 결함을 넣는다. 오류 코드가 기대와 같고
 * 두 달 모두 0행이어야 허용 목록에 둘 수 있다 — 한 요청이 여러 ClickHouse 파티션에 걸치면 원자성 범위는 각 파티션이다. 파싱 오류(26·27·69·117·
 * 130·131)와 SELECT 형변환 오류(72)를 따로 본다. ClickHouse 이미지를 올리면 이 시험이 먼저 통과해야 한다.
 */
@Testcontainers
class InputDefectCodesEvidenceTest {

	private lateinit var client: ClickHouseHttpClient

	@BeforeEach
	fun setUp() {
		client = ClickHouseHttpClient(url())
		ClickHouseSchemaMigrator(client).apply()
		client.execute("TRUNCATE TABLE IF EXISTS ${TelemetryEventsSink.TABLE}")
		client.execute("TRUNCATE TABLE IF EXISTS ${TelemetryMetricPointsSink.TABLE}")
	}

	@ParameterizedTest(name = "{0} → 코드 {2}")
	@MethodSource("defects")
	@DisplayName("마지막 행의 결함은 요청 전체를 쓰기 전에 실패시키고 두 달 모두 0행이다")
	fun defectWritesNothingInEitherMonth(name: String, metric: Boolean, expectedCode: Int, corrupt: (String) -> String) {
		val boundary = AnalysisWriteBoundary(AnalysisSamples.TENANT, deletedBefore = null, policyEpoch = 0)
		val versioning = RowVersioning.live(Instant.parse("2026-11-01T02:00:00Z"))
		val (sink, lines) = if (metric) {
			val points = TelemetryMetricPointsSink(client)
			points to points.encode(listOf(OCTOBER, NOVEMBER).mapIndexed { i, time -> metricAt(time, ('c' + i)) }, versioning, boundary)
		} else {
			val events = TelemetryEventsSink(client)
			events to events.encode(listOf(OCTOBER, NOVEMBER).mapIndexed { i, time -> eventAt(time, ('a' + i)) }, versioning, boundary)
		}
		val corrupted = lines.dropLast(1) + corrupt(lines.last())
		assertThat(corrupted.last()).describedAs(name).isNotEqualTo(lines.last())

		val thrown = catchThrowable { sink.send(corrupted, boundary) }

		val code = (thrown as? TelemetrySinkRejectedException)?.code ?: (thrown as? TelemetrySinkUnavailableException)?.code
		assertThat(code).describedAs("$name: ${thrown?.javaClass?.simpleName}").isEqualTo(expectedCode)
		assertThat(ClickHouseErrorCodes.INPUT_DEFECT_CODES).contains(expectedCode)
		assertThat(rowsByMonth(sink.table)).describedAs(name).isEmpty()

		// 대조군 — 결함이 없으면 두 달에 한 행씩 남는다. 같은 형식으로 쓴 요청이 실제로 두 파티션에 걸친다는 확인이다.
		sink.send(lines, boundary)
		assertThat(rowsByMonth(sink.table)).containsExactly("202610\t1", "202611\t1")
	}

	private fun rowsByMonth(table: String): List<String> =
		client.execute("SELECT toYYYYMM(source_time) AS m, count() FROM $table GROUP BY m ORDER BY m FORMAT TSV").lines().filter { it.isNotBlank() }

	private fun eventAt(time: Instant, seed: Char) = event(
		EventObservation(envelope = envelope(observationId = hex(seed)).copy(sourceTime = EpochNanos.of(time)), eventType = EventType.TURN),
	)

	private fun metricAt(time: Instant, seed: Char) = metricPoint(
		metricPointObservation(hex(seed)).let { it.copy(envelope = it.envelope.copy(sourceTime = EpochNanos.of(time))) },
	)

	private fun url(): String = "http://${clickhouse.host}:${clickhouse.getMappedPort(HTTP_PORT)}"

	companion object {
		private const val HTTP_PORT: Int = 8123
		private val OCTOBER: Instant = Instant.parse("2026-10-31T23:00:00Z")
		private val NOVEMBER: Instant = Instant.parse("2026-11-01T01:00:00Z")

		/** 첫 번째로 나오는 `"<key>":<값>` 의 값을 [replacement] 로 바꾼다. 값은 문자열·숫자·null·배열 중 하나다. */
		private fun replaceValue(line: String, key: String, replacement: String): String {
			val match = Regex("\"" + Regex.escape(key) + "\":(\"(?:[^\"\\\\]|\\\\.)*\"|\\[[^]]*]|[^,}]+)").find(line)
				?: error("$key 가 행에 없다")
			return line.replaceRange(match.range, "\"$key\":$replacement")
		}

		/**
		 * SELECT 형변환을 보는 열 — Nullable 이 아닌 `Array(Float64)`(`arrayMap(x -> toFloat64(x), …)`). 24.8 에서 `Nullable(Float64)` 열은
		 * `toFloat64('abc')` 를 오류 없이 NULL 로 넣는다 — 그 경우는 결함 신호가 아니고, 비유한·비숫자 값은 인코더가 먼저 거부한다.
		 */
		private fun float64ArrayColumn(): String = AnalysisColumns.METRIC_POINTS.first { it.type == "Array(Float64)" }.name

		@JvmStatic
		fun defects(): Stream<Arguments> = Stream.of(
			Arguments.of("UInt16 에 문자열", false, ClickHouseErrorCodes.CANNOT_PARSE_INPUT_ASSERTION_FAILED, { l: String -> replaceValue(l, "schema_version", "\"zz\"") }),
			Arguments.of("끊긴 JSON", false, ClickHouseErrorCodes.CANNOT_PARSE_QUOTED_STRING, { l: String -> l.substringBefore("\"masking_version\"") + "\"masking_version\":\"unterminated" }),
			Arguments.of("Decimal 자릿수 초과", false, ClickHouseErrorCodes.ARGUMENT_OUT_OF_BOUND, { l: String -> replaceValue(l, "cost_reported_usd", "123456789012345678901234567890123") }),
			Arguments.of("선언 밖 키", false, ClickHouseErrorCodes.INCORRECT_DATA, { l: String -> "{\"zz_unknown\":1," + l.removePrefix("{") }),
			Arguments.of("Array 에 문자열", false, ClickHouseErrorCodes.CANNOT_READ_ARRAY_FROM_TEXT, { l: String -> replaceValue(l, "team_ids_as_of", "\"x\"") }),
			Arguments.of("FixedString(64) 초과", false, ClickHouseErrorCodes.TOO_LARGE_STRING_SIZE, { l: String -> replaceValue(l, "analysis_hash", "\"" + "b".repeat(65) + "\"") }),
			Arguments.of("Float64 형변환(SELECT)", true, ClickHouseErrorCodes.CANNOT_PARSE_NUMBER, { l: String -> replaceValue(l, float64ArrayColumn(), "[\"abc\"]") }),
		)

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
