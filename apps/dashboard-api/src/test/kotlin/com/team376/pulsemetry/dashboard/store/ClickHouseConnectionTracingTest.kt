package com.team376.pulsemetry.dashboard.store

import com.sun.net.httpserver.HttpServer
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext
import io.micrometer.tracing.otel.bridge.OtelTracer
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.samplers.Sampler
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * dashboard-api ClickHouse 호출의 client span(ADR 0079). URL query 의 SQL·`param_*` 값과 오류 본문을 span 에 싣지 않는다.
 * ClickHouse 없이 응답 상태·예외 코드 header·본문만 흉내 낸다.
 */
class ClickHouseConnectionTracingTest {

	private lateinit var server: HttpServer
	private var status: Int = 200
	private var exceptionCode: String? = null
	private val exporter = InMemorySpanExporter.create()
	private val provider = SdkTracerProvider.builder().setSampler(Sampler.alwaysOn()).addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()
	private val tracer = OtelTracer(provider.get("test"), OtelCurrentTraceContext()) { }

	@BeforeEach
	fun startStub() {
		server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
		server.createContext("/") { exchange ->
			val bytes = (if (status == 200) """{"n":"1"}""" + "\n" else "Code: 60. DB::Exception: Table CANARY_table doesn't exist CANARY")
				.toByteArray(StandardCharsets.UTF_8)
			exceptionCode?.let { exchange.responseHeaders.add("X-ClickHouse-Exception-Code", it) }
			exchange.sendResponseHeaders(status, bytes.size.toLong())
			exchange.responseBody.use { it.write(bytes) }
		}
		server.start()
	}

	@AfterEach
	fun stop() {
		server.stop(0)
		provider.close()
	}

	private fun connection(tracer: io.micrometer.tracing.Tracer = this.tracer) = ClickHouseConnection(
		"http://127.0.0.1:${server.address.port}", "default", "reader", "CANARY-password", Duration.ofSeconds(5),
		JsonMapper.builder().build(), tracer = tracer,
	)

	private fun select(connection: ClickHouseConnection = connection()) = connection.query(
		"SELECT count() AS n FROM telemetry_events WHERE tenant_id = {tenant:String}",
		settings = mapOf("readonly" to "2"),
		params = mapOf("tenant" to ClickHouseParam.string("CANARY-tenant")),
	) { it.path("n").asString() }

	private fun <T> inParent(block: () -> T): Pair<T, io.micrometer.tracing.Span> {
		val parent = tracer.nextSpan().name("http get").start()
		val result = tracer.withSpan(parent).use { block() }
		parent.end()
		return result to parent
	}

	private fun SpanData.text(): String =
		(attributes.asMap().values.map { it.toString() } + name + status.description +
			events.flatMap { e -> e.attributes.asMap().values.map { it.toString() } + e.name }).joinToString()

	@Test
	@DisplayName("성공 호출은 operation·table·outcome 만 가진 client span 이고 현재 span 의 자식이다")
	fun successIsAChildClientSpan() {
		val (rows, parent) = inParent { select() }
		assertThat(rows).containsExactly("1")

		val span = exporter.finishedSpanItems.single { it.kind == SpanKind.CLIENT }
		assertThat(span.name).isEqualTo("clickhouse select telemetry_events")
		assertThat(span.parentSpanId).isEqualTo(parent.context().spanId())
		// remoteServiceName 이 붙이는 peer.service 밖에는 고정 속성 넷뿐이다.
		assertThat(span.attributes.asMap().keys.map { it.key })
			.contains("db.system.name", "pulsemetry.operation", "pulsemetry.table", "pulsemetry.outcome")
			.isSubsetOf("db.system.name", "pulsemetry.operation", "pulsemetry.table", "pulsemetry.outcome", "peer.service")
		assertThat(span.attributes.get(AttributeKey.stringKey("pulsemetry.outcome"))).isEqualTo("success")
		assertThat(span.text()).doesNotContain("CANARY", "tenant_id", "127.0.0.1")
	}

	@Test
	@DisplayName("거부된 문장은 ERROR 이고 분류는 예외 이름뿐이다 — 응답 본문·SQL 은 span 에 없다")
	fun rejectedStatementIsErrorWithoutDetail() {
		status = 404
		exceptionCode = "60"
		inParent { assertThatThrownBy { select() }.isInstanceOf(StoreQueryRejectedException::class.java) }

		val span = exporter.finishedSpanItems.single { it.kind == SpanKind.CLIENT }
		assertThat(span.status.statusCode).isEqualTo(StatusCode.ERROR)
		assertThat(span.status.description).isEmpty()
		assertThat(span.attributes.get(AttributeKey.stringKey("error.type"))).isEqualTo("StoreQueryRejectedException")
		assertThat(span.attributes.get(AttributeKey.stringKey("pulsemetry.outcome"))).isEqualTo("rejected")
		assertThat(span.text()).doesNotContain("CANARY", "DB::Exception")
	}

	@Test
	@DisplayName("상한 초과는 limit_exceeded 다")
	fun limitExceededIsClassified() {
		status = 500
		exceptionCode = "159"
		inParent { assertThatThrownBy { select() }.isInstanceOf(StoreLimitExceededException::class.java) }

		val span = exporter.finishedSpanItems.single { it.kind == SpanKind.CLIENT }
		assertThat(span.attributes.get(AttributeKey.stringKey("error.type"))).isEqualTo("StoreLimitExceededException")
		assertThat(span.attributes.get(AttributeKey.stringKey("pulsemetry.outcome"))).isEqualTo("limit_exceeded")
	}

	@Test
	@DisplayName("부모 span 이 없으면(기동 때 스키마 적용) span 을 만들지 않는다")
	fun noParentNoSpan() {
		assertThat(select()).containsExactly("1")
		assertThat(exporter.finishedSpanItems).isEmpty()
	}

	@Test
	@DisplayName("tracer 를 주지 않으면 NOOP 이다")
	fun noopByDefault() {
		val plain = ClickHouseConnection("http://127.0.0.1:${server.address.port}", "default", "reader", "", Duration.ofSeconds(5), JsonMapper.builder().build())
		inParent { assertThat(select(plain)).containsExactly("1") }
		assertThat(exporter.finishedSpanItems.filter { it.kind == SpanKind.CLIENT }).isEmpty()
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource(
		delimiter = '|',
		value = [
			"INSERT INTO dashboard_cache.snapshot_intake SELECT * FROM telemetry_events WHERE x = 1 | insert | snapshot_intake",
			"WITH t AS (SELECT 1) SELECT sum(v) FROM snapshot_usage | select | snapshot_usage",
			"  select count() from snapshot_observed_days | select | snapshot_observed_days",
			"DELETE FROM snapshot_member_activity WHERE build_id IN {builds:Array(String)} | delete | snapshot_member_activity",
			"CREATE TABLE IF NOT EXISTS snapshot_intake (x UInt8) ENGINE = Memory | schema | snapshot_intake",
			"CREATE MATERIALIZED VIEW IF NOT EXISTS snapshot_intake_days TO snapshot_observed_days AS SELECT 1 | schema | snapshot_intake_days",
			"SELECT EXTRACT(DAY FROM ts) FROM telemetry_events | select | telemetry_events",
			"SELECT 1 | select | other",
			"SELECT * FROM secret_customer_table | select | other",
			"-- comment then SELECT 1 FROM telemetry_events | other | telemetry_events",
			"OPTIMIZE TABLE snapshot_usage | other | other",
		],
	)
	fun operationAndTableComeFromFixedLists(sql: String, operation: String, table: String) {
		assertThat(ClickHouseConnection.operationOf(sql)).isEqualTo(operation).isIn(ClickHouseConnection.OPERATIONS)
		assertThat(ClickHouseConnection.tableOf(sql)).isEqualTo(table)
	}
}
