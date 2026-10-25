package com.team376.pulsemetry.persistence.telemetry

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
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * ClickHouse 호출의 client span(ADR 0069). URL query 에 있는 SQL·파라미터와 오류 본문을 span 에 싣지 않는다.
 * ClickHouse 없이 응답 상태와 본문만 흉내 낸다.
 */
class ClickHouseClientTracingTest {

	private lateinit var server: HttpServer
	private var status: Int = 200
	private val exporter = InMemorySpanExporter.create()
	private val provider = SdkTracerProvider.builder().setSampler(Sampler.alwaysOn()).addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()
	private val tracer = OtelTracer(provider.get("test"), OtelCurrentTraceContext()) { }

	@BeforeEach
	fun startStub() {
		server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
		server.createContext("/") { exchange ->
			val bytes = (if (status == 200) "1\n" else "Code: 60. DB::Exception: Table default.secret_table doesn't exist CANARY").toByteArray(StandardCharsets.UTF_8)
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

	private fun client() = ClickHouseHttpClient("http://127.0.0.1:${server.address.port}", tracer = tracer)

	private fun execute(operation: String = "select", table: String = "telemetry_events") =
		client().execute(
			"SELECT count() FROM telemetry_events WHERE tenant_id = {tenant:String}",
			params = mapOf("tenant" to "CANARY-tenant"),
			queryId = "CANARY-query",
			operation = operation,
			table = table,
		)

	private fun SpanData.text(): String =
		(attributes.asMap().values.map { it.toString() } + name + status.description +
			events.flatMap { e -> e.attributes.asMap().values.map { it.toString() } + e.name }).joinToString()

	@Test
	@DisplayName("성공 호출은 operation·table·outcome 만 가진 client span 이고 현재 span 의 자식이다")
	fun successIsAChildClientSpan() {
		val parent = tracer.nextSpan().name("telemetry process").start()
		tracer.withSpan(parent).use { execute() }
		parent.end()

		val span = exporter.finishedSpanItems.single { it.kind == SpanKind.CLIENT }
		assertThat(span.name).isEqualTo("clickhouse select telemetry_events")
		assertThat(span.parentSpanId).isEqualTo(parent.context().spanId())
		assertThat(span.attributes.get(AttributeKey.stringKey("db.system.name"))).isEqualTo("clickhouse")
		assertThat(span.attributes.get(AttributeKey.stringKey("pulsemetry.operation"))).isEqualTo("select")
		assertThat(span.attributes.get(AttributeKey.stringKey("pulsemetry.table"))).isEqualTo("telemetry_events")
		assertThat(span.attributes.get(AttributeKey.stringKey("pulsemetry.outcome"))).isEqualTo("success")
		assertThat(span.status.statusCode).isNotEqualTo(StatusCode.ERROR)
		assertThat(span.text()).doesNotContain("SELECT", "CANARY", "query=", "param_", "127.0.0.1")
	}

	@Test
	@DisplayName("4xx 는 permanent_error, 5xx 는 transient_error 이고 ERROR status 에 예외 클래스 이름만 남는다")
	fun failuresKeepOnlyClassNames() {
		status = 404
		assertThatThrownBy { execute(operation = "insert") }.isInstanceOf(TelemetrySinkRejectedException::class.java)
		status = 503
		assertThatThrownBy { execute(operation = "insert") }.isInstanceOf(TelemetrySinkUnavailableException::class.java)

		val (rejected, unavailable) = exporter.finishedSpanItems
		assertThat(rejected.attributes.get(AttributeKey.stringKey("pulsemetry.outcome"))).isEqualTo("permanent_error")
		assertThat(rejected.attributes.get(AttributeKey.stringKey("error.type"))).isEqualTo("TelemetrySinkRejectedException")
		assertThat(unavailable.attributes.get(AttributeKey.stringKey("pulsemetry.outcome"))).isEqualTo("transient_error")
		assertThat(unavailable.attributes.get(AttributeKey.stringKey("error.type"))).isEqualTo("TelemetrySinkUnavailableException")
		for (span in exporter.finishedSpanItems) {
			// Tempo 의 status = error 검색에 잡히고, 설명은 비어 있다.
			assertThat(span.status.statusCode).isEqualTo(StatusCode.ERROR)
			assertThat(span.status.description).isEmpty()
			assertThat(span.text()).doesNotContain("CANARY", "secret_table", "DB::Exception")
			// 이벤트는 표식 하나뿐이다 — 메시지 속성이 없고 stack frame 이 없다.
			val event = span.events.single()
			assertThat(event.name).isEqualTo("exception")
			assertThat(event.attributes.get(AttributeKey.stringKey("exception.message"))).isNull()
			assertThat(event.attributes.get(AttributeKey.stringKey("exception.stacktrace")).orEmpty()).doesNotContain("\tat ")
		}
	}

	@Test
	@DisplayName("허용목록 밖의 operation·table 은 other 로 합친다")
	fun unknownLabelsCollapse() {
		execute(operation = "truncate", table = "secret_table")
		val span = exporter.finishedSpanItems.single()
		assertThat(span.name).isEqualTo("clickhouse other other")
		assertThat(span.text()).doesNotContain("truncate", "secret_table")
	}

	@Test
	@DisplayName("기본(NOOP) 클라이언트는 span 을 만들지 않는다")
	fun noopByDefault() {
		ClickHouseHttpClient("http://127.0.0.1:${server.address.port}").execute("SELECT 1")
		assertThat(exporter.finishedSpanItems).isEmpty()
	}
}
