package com.team376.pulsemetry.persistence.telemetry

import com.team376.pulsemetry.telemetry.adapter.observation.EpochNanos
import com.team376.pulsemetry.telemetry.adapter.observation.NormalizationStats
import com.team376.pulsemetry.telemetry.adapter.observation.ObservationSignal
import com.team376.pulsemetry.telemetry.adapter.observation.SourceTimeRejection
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
 * 수신 ledger sink(ADR 0021 §1). 한 행은 (receipt, signal, archive product) 하나의 수신 사실이다 — 정규화가 통째로
 * 실패해도 남고, 같은 receipt 의 저장 재시도는 한 행으로 수렴하며, HTTP 재전송(새 receipt)은 새 행이다.
 */
@Testcontainers
class IngestLedgerSinkTest {

	private lateinit var client: ClickHouseHttpClient
	private lateinit var sink: IngestLedgerSink

	private val receipt = LedgerReceipt(
		tenantId = AnalysisSamples.TENANT,
		installationId = AnalysisSamples.INSTALLATION,
		receivedTime = Instant.parse("2026-01-01T00:00:05.123456789Z"),
		receiptId = "0f0e0d0c-0000-4000-8000-000000000001",
		signal = ObservationSignal.LOG,
		maskingVersion = "masking-v2",
	)

	@BeforeEach
	fun setUp() {
		client = ClickHouseHttpClient("http://${clickhouse.host}:${clickhouse.getMappedPort(HTTP_PORT)}")
		ClickHouseSchemaMigrator(client).apply()
		client.execute("TRUNCATE TABLE IF EXISTS ${IngestLedgerSink.TABLE}")
		sink = IngestLedgerSink(client)
	}

	private fun stats(): NormalizationStats = NormalizationStats().apply {
		repeat(4) { recordReceived() }
		recordAccepted(EpochNanos.of(Instant.parse("2025-12-31T23:59:00Z")))
		recordAccepted(EpochNanos.of(Instant.parse("2026-01-01T00:00:01.000000001Z")))
		recordRejected(SourceTimeRejection.entries.first())
		recordExcludedSpan()
	}

	@Test
	@DisplayName("선언이 system.columns 와 같다")
	fun declarationMatchesTheTable() {
		val columns = client.execute(
			"SELECT name, type FROM system.columns WHERE database = currentDatabase() AND table = '${IngestLedgerSink.TABLE}' " +
				"ORDER BY position FORMAT TSVRaw",
		).trim().lines().map { line -> line.split('\t').let { it[0] to it[1] } }

		assertThat(IngestLedgerSink.COLUMNS.map { it.name to it.type }).containsExactlyElementsOf(columns)
	}

	@Test
	@DisplayName("정규화를 마친 문서 — 받은 수·거부 수(시각 거부 + 제외 스팬)·유효 시각 범위를 남긴다")
	fun normalizedDocument() {
		sink.insert(listOf(IngestLedgerEntry.normalized(receipt, "claude_code", "s3://raw/claude_code/logs/x.json", stats())))

		assertThat(row()).isEqualTo(
			listOf(
				AnalysisSamples.TENANT, AnalysisSamples.INSTALLATION, "2026-01-01 00:00:05.123456789", receipt.receiptId, "log",
				"claude_code", "2025-12-31 23:59:00.000000000", "2026-01-01 00:00:01.000000001", "4", "2",
				"s3://raw/claude_code/logs/x.json", "masking-v2",
			).joinToString("\t"),
		)
	}

	@Test
	@DisplayName("정규화가 통째로 실패한 문서도 남는다 — 시각 범위 null, 받은 것 전부가 거부")
	fun unnormalizedDocument() {
		sink.insert(listOf(IngestLedgerEntry.unnormalized(receipt, "codex", null, received = 7)))

		assertThat(row().split('\t').subList(5, 12)).containsExactly("codex", NULL, NULL, "7", "7", NULL, "masking-v2")
	}

	@Test
	@DisplayName("같은 receipt 의 저장 재시도는 한 행, 다른 receipt(HTTP 재전송)·다른 product 는 다른 행이다")
	fun retriesCollapseAndResendsDoNot() {
		val entry = IngestLedgerEntry.normalized(receipt, "claude_code", null, stats())
		sink.insert(listOf(entry, IngestLedgerEntry.normalized(receipt, "codex", null, stats())))
		sink.insert(listOf(entry))
		sink.insert(listOf(entry.copy(receipt = receipt.copy(receiptId = "0f0e0d0c-0000-4000-8000-000000000002"))))

		assertThat(client.execute("SELECT count() FROM ${IngestLedgerSink.TABLE} FINAL").trim()).isEqualTo("3")
		assertThat(sink.insert(emptyList())).isZero()
	}

	@Test
	@DisplayName("행의 불변식 — 거부 수는 받은 수 이하, 시각 범위는 양끝이 함께, 신원이 있어야 한다")
	fun entryInvariants() {
		assertThatThrownBy { IngestLedgerEntry.unnormalized(receipt, "codex", null, received = -1) }
			.isInstanceOf(IllegalArgumentException::class.java)
		assertThatThrownBy { IngestLedgerEntry(receipt, "codex", EpochNanos(1), null, 1, 0, null) }
			.isInstanceOf(IllegalArgumentException::class.java)
		assertThatThrownBy { IngestLedgerEntry(receipt, "codex", EpochNanos(2), EpochNanos(1), 1, 0, null) }
			.isInstanceOf(IllegalArgumentException::class.java)
		assertThatThrownBy { receipt.copy(tenantId = "") }.isInstanceOf(IllegalArgumentException::class.java)
	}

	private fun row(): String = client.execute(
		"SELECT tenant_id, installation_id, toString(received_time), receipt_id, signal, product, toString(source_time_min), " +
			"toString(source_time_max), record_count, rejected_count, archive_ref, masking_version FROM ${IngestLedgerSink.TABLE} FINAL FORMAT TSVRaw",
	).trim()

	companion object {
		private const val HTTP_PORT: Int = 8123
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
