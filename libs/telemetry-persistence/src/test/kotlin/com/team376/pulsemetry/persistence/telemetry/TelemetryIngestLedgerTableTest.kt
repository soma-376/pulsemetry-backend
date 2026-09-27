package com.team376.pulsemetry.persistence.telemetry

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * 수신 ledger 의 DDL(`V3__telemetry_ingest_ledger.sql`)을 실제 ClickHouse 위에서 고정한다 (ADR 0021 §1).
 *
 * 행은 손으로 쓴다 — writer 는 이 테스트의 대상이 아니다. 여기서 보는 것은 컬럼과 키가 ADR 의 의미를
 * 지키는가다: 같은 receipt 의 저장 재시도는 한 행으로 수렴하고, HTTP 재전송(새 receipt)은 합쳐지지 않는다.
 */
@Testcontainers
class TelemetryIngestLedgerTableTest {

	private lateinit var client: ClickHouseHttpClient

	@BeforeEach
	fun setUp() {
		client = ClickHouseHttpClient("http://${clickhouse.host}:${clickhouse.getMappedPort(HTTP_PORT)}")
		ClickHouseSchemaMigrator(client).apply()
		client.execute("TRUNCATE TABLE IF EXISTS $LEDGER")
	}

	@Test
	@DisplayName("두 번 더 적용해도 안전하고 ADR 0021 의 열두 컬럼을 이 순서·타입으로 만든다")
	fun theLedgerHasTheDecidedColumns() {
		ClickHouseSchemaMigrator(client).apply()
		ClickHouseSchemaMigrator(client).apply()

		val columns = query(
			"SELECT name, type FROM system.columns WHERE database = currentDatabase() AND table = '$LEDGER' " +
				"ORDER BY position FORMAT TSVRaw",
		).lines().map { line -> line.split('\t').let { it[0] to it[1] } }

		assertThat(columns).containsExactly(
			"tenant_id" to "LowCardinality(String)",
			"installation_id" to "String",
			"received_time" to "DateTime64(9, 'UTC')",
			"receipt_id" to "String",
			"signal" to "LowCardinality(String)",
			"product" to "LowCardinality(String)",
			"source_time_min" to "Nullable(DateTime64(9, 'UTC'))",
			"source_time_max" to "Nullable(DateTime64(9, 'UTC'))",
			"record_count" to "UInt32",
			"rejected_count" to "UInt32",
			"archive_ref" to "Nullable(String)",
			"masking_version" to "String",
		)
	}

	@Test
	@DisplayName("엔진·파티션·정렬 키 — 수신 월 파티션이고 TTL 이 없다")
	fun engineAndKeys() {
		val row = query(
			"SELECT engine, partition_key, sorting_key FROM system.tables " +
				"WHERE database = currentDatabase() AND name = '$LEDGER' FORMAT TSV",
		).split('\t')

		assertThat(row[0]).isEqualTo("ReplacingMergeTree")
		assertThat(row[1]).isEqualTo("toYYYYMM(received_time)")
		assertThat(row[2]).isEqualTo("tenant_id, installation_id, received_time, receipt_id, signal, product")
		assertThat(query("SELECT engine_full FROM system.tables WHERE database = currentDatabase() AND name = '$LEDGER'"))
			.doesNotContain("TTL")
	}

	@Test
	@DisplayName("같은 receipt 의 저장 재시도는 한 행으로 수렴하고, HTTP 재전송(새 receipt)은 따로 남는다")
	fun retriesCollapseButResendsDoNot() {
		val first = row(receiptId = RECEIPT_1)
		insert(first)
		insert(first)
		insert(row(receiptId = RECEIPT_2))

		assertThat(query("SELECT count() FROM $LEDGER FINAL")).isEqualTo("2")
		assertThat(query("SELECT sum(record_count) FROM $LEDGER FINAL")).isEqualTo("6")
	}

	@Test
	@DisplayName("source_time 을 하나도 얻지 못한 push 도 기록된다 — 범위는 null, 거부 수는 받은 수")
	fun aPushWithoutSourceTimeIsStillRecorded() {
		insert(row(receiptId = RECEIPT_1, sourceTimeMin = null, sourceTimeMax = null, rejected = 3))

		assertThat(query("SELECT count() FROM $LEDGER FINAL WHERE isNull(source_time_min) AND rejected_count = record_count"))
			.isEqualTo("1")
	}

	private fun query(sql: String): String = client.execute(sql).trim()

	private fun row(
		receiptId: String,
		sourceTimeMin: String? = "2026-09-24 15:29:00.000000001",
		sourceTimeMax: String? = "2026-09-24 15:29:59.999999999",
		rejected: Int = 0,
	): String {
		fun str(value: String?): String = if (value == null) "null" else "\"$value\""
		return """{"tenant_id":"$TENANT","installation_id":"$INSTALLATION",""" +
			""""received_time":"2026-09-24 15:30:00.123456789","receipt_id":"$receiptId",""" +
			""""signal":"log","product":"codex","source_time_min":${str(sourceTimeMin)},""" +
			""""source_time_max":${str(sourceTimeMax)},"record_count":3,"rejected_count":$rejected,""" +
			""""archive_ref":null,"masking_version":"test"}"""
	}

	private fun insert(row: String) {
		client.execute("INSERT INTO $LEDGER FORMAT JSONEachRow", row.toByteArray())
	}

	companion object {
		private const val HTTP_PORT: Int = 8123
		private const val LEDGER: String = "telemetry_ingest_ledger"
		private const val TENANT: String = "33333333-3333-3333-3333-333333333333"
		private const val INSTALLATION: String = "44444444-4444-4444-4444-444444444444"
		private const val RECEIPT_1: String = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
		private const val RECEIPT_2: String = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"

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
