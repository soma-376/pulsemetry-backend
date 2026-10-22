package com.team376.pulsemetry.persistence.telemetry

import com.sun.net.httpserver.HttpServer
import com.team376.pulsemetry.telemetry.observability.TelemetryMetric
import com.team376.pulsemetry.telemetry.observability.TelemetryObserver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

/**
 * 보존·백필 쪽 ClickHouse 호출이 운영 지표에 `operation`·`table` 을 밝히는지 고정한다(ADR 0068). 기본값 `other` 로 남으면
 * fence 조회와 분석 INSERT 가 대시보드에서 구별되지 않는다. ClickHouse 없이 돈다 — 응답 본문만 흉내 낸다.
 */
class ClickHouseRequestLabelTest {

	private lateinit var server: HttpServer
	private var body: String = ""
	private val recorded = mutableListOf<Map<String, String>>()
	private val observer = TelemetryObserver { metric, _, tags ->
		if (metric == TelemetryMetric.CLICKHOUSE_REQUESTS) recorded += tags.toMap()
	}

	@BeforeEach
	fun startStub() {
		server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
		server.createContext("/") { exchange ->
			val bytes = body.toByteArray(StandardCharsets.UTF_8)
			exchange.sendResponseHeaders(200, bytes.size.toLong())
			exchange.responseBody.use { it.write(bytes) }
		}
		server.start()
	}

	@AfterEach
	fun stopStub() {
		server.stop(0)
	}

	private fun client() = ClickHouseHttpClient("http://127.0.0.1:${server.address.port}", observer = observer)

	private fun labels() = recorded.map { it["operation"] to it["table"] }

	@Test
	@DisplayName("fence 조회는 select, fence 쓰기는 insert 다 — 둘 다 fence 테이블이다")
	fun retentionFence() {
		body = "2026-01-01 00:00:00.000000000\t3\n"
		val fence = RetentionFence(client())

		fence.current("t")
		fence.write("t", RetentionFence.Value(Instant.parse("2026-01-01T00:00:00Z"), 3))

		assertThat(labels()).containsExactly("select" to "telemetry_retention_fence", "insert" to "telemetry_retention_fence")
	}

	@Test
	@DisplayName("삭제 대상 집계는 select, 삭제는 delete 다 — 두 분석 테이블을 각각 밝힌다")
	fun retentionPurge() {
		body = "0\t0\n"
		val purge = RetentionPurge(client())

		purge.count("t", Instant.parse("2026-01-01T00:00:00Z"))
		purge.delete("t", Instant.parse("2026-01-01T00:00:00Z"))

		assertThat(labels()).containsExactly(
			"select" to "telemetry_events", "select" to "telemetry_metric_points",
			"delete" to "telemetry_events", "delete" to "telemetry_metric_points",
		)
	}

	@Test
	@DisplayName("drain 의 process list 조회와 백필 원천 조회도 select 다")
	fun drainAndBackfillSource() {
		body = "0\n"
		InsertDrain(client(), sleep = {}).awaitFinished(setOf("q"), Duration.ZERO, Duration.ofMillis(1))
		body = ""
		InsertDrain(client()).running()
		PreLedgerHistorySource(client()).tenantIds()

		assertThat(recorded.map { it["operation"] }).containsOnly("select")
		assertThat(labels().map { it.second }).containsExactly("system.processes", "system.processes", "enriched_events")
	}
}
