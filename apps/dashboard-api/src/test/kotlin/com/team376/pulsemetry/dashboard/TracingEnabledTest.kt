package com.team376.pulsemetry.dashboard

import com.team376.pulsemetry.dashboard.authentication.DashboardPrincipal
import com.team376.pulsemetry.dashboard.authentication.Role
import com.team376.pulsemetry.dashboard.store.ClickHouseConnection
import com.team376.pulsemetry.dashboard.support.AbstractDashboardApiTest
import com.team376.pulsemetry.dashboard.support.DashboardHttp
import com.team376.pulsemetry.dashboard.support.DashboardTestStores
import com.team376.pulsemetry.dashboard.support.SourceFixtures
import com.team376.pulsemetry.dashboard.support.TestDashboardAuthenticator
import com.team376.pulsemetry.dashboard.support.TestSpans
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.sdk.trace.data.SpanData
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.util.UUID

/**
 * 플랫폼 trace 를 켠 dashboard-api(ADR 0079). Collector endpoint 는 닿지 않는 주소다 — 내보내기 실패가 응답을 바꾸지 않는다.
 * 설정이 달라 컨텍스트가 따로 뜬다.
 */
@AutoConfigureTracing
@Import(TestSpans.Config::class)
@TestPropertySource(
	properties = [
		"management.tracing.export.enabled=true",
		"management.tracing.sampling.probability=1.0",
		"management.opentelemetry.tracing.export.otlp.endpoint=http://127.0.0.1:9/v1/traces",
		"pulsemetry.tracing.environment=test",
		"pulsemetry.tracing.cluster=test-cluster",
		"pulsemetry.tracing.pod=dashboard-api-0",
	],
)
@ExtendWith(OutputCaptureExtension::class)
class TracingEnabledTest : AbstractDashboardApiTest() {

	@Value("\${local.management.port}")
	private var managementPort: Int = 0

	@BeforeEach
	fun clear() = TestSpans.exporter.reset()

	private fun admin(tenant: UUID): Map<String, String> =
		mapOf("Authorization" to TestDashboardAuthenticator.header(DashboardPrincipal(tenant, UUID.randomUUID(), Role.ADMIN)))

	private fun SpanData.text(): List<String> =
		attributes.asMap().values.map { it.toString() } + name + status.description +
			events.flatMap { e -> e.attributes.asMap().values.map { it.toString() } + e.name }

	private fun serverSpan(spans: List<SpanData>): SpanData = spans.single { it.kind == SpanKind.SERVER }

	@Test
	@DisplayName("개요 요청의 서버 span 아래에 ClickHouse client span 이 붙고 어느 span 에도 조직 ID·SQL 이 없다")
	fun overviewTraceHasClickHouseChildren(output: CapturedOutput) {
		SourceFixtures.completeBackfill()
		val tenant = DashboardTestStores.insertTenant()
		val response = http.send(
			"/api/v1/organizations/$tenant/analytics/overview?startDate=2026-09-07&endDate=2026-09-07&compare=none",
			headers = admin(tenant),
		)
		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200)

		val spans = TestSpans.await { spans -> spans.any { it.kind == SpanKind.SERVER } && spans.any { it.kind == SpanKind.CLIENT } }
		val server = serverSpan(spans)
		assertThat(server.name).isEqualTo("http get /api/v1/organizations/{organizationId}/analytics/overview")
		assertThat(server.attributes.asMap().keys.map { it.key }).doesNotContain("http.url")

		// 예약 작업의 trace 도 같은 exporter 에 모이므로 같은 trace 만 본다.
		val clients = spans.filter { it.kind == SpanKind.CLIENT && it.traceId == server.traceId }
		assertThat(clients).isNotEmpty()
		assertThat(clients).allSatisfy { client ->
			assertThat(client.name).startsWith("clickhouse ")
			assertThat(client.attributes.get(AttributeKey.stringKey("db.system.name"))).isEqualTo("clickhouse")
			assertThat(client.attributes.get(AttributeKey.stringKey("pulsemetry.operation"))).isIn(ClickHouseConnection.OPERATIONS)
			assertThat(client.attributes.get(AttributeKey.stringKey("pulsemetry.table"))).isIn(ClickHouseConnection.TABLES + "other")
			assertThat(client.attributes.get(AttributeKey.stringKey("pulsemetry.outcome"))).isEqualTo("success")
		}
		assertThat(clients.map { it.name }).anyMatch { it.matches(Regex("clickhouse (select|insert) snapshot_[a-z_]+")) }

		val text = spans.filter { it.traceId == server.traceId }.flatMap { it.text() }
		assertThat(text).noneMatch { it.contains(tenant.toString()) || it.contains("SELECT", ignoreCase = false) }

		// sampled 요청은 서버 span 안에서 완료 한 줄을 남긴다. route 는 경로 템플릿이다.
		val completed = output.all.lines().single { it.contains("event=platform_trace_completed stage=dashboard") }
		assertThat(completed)
			.contains("method=GET route=/api/v1/organizations/{organizationId}/analytics/overview status=200 outcome=success")
			.contains("trace_id=${server.traceId} span_id=${server.spanId}")
			.doesNotContain(tenant.toString())
	}

	@Test
	@DisplayName("공개 요청의 traceparent 는 부모가 되지 않는다 — 서버 span 은 새 root 이고 resource 는 고정 이름이다")
	fun inboundTraceparentIsIgnored() {
		val outside = "0af7651916cd43dd8448eb211c80319c"
		val response = http.send(
			"/api/v1/organizations/${UUID.randomUUID()}/alerts",
			headers = mapOf("traceparent" to "00-$outside-b7ad6b7169203331-01", "baggage" to "tenant=leak"),
		)

		assertThat(response.statusCode()).isEqualTo(401)
		val server = serverSpan(TestSpans.await { spans -> spans.any { it.kind == SpanKind.SERVER } })
		assertThat(server.traceId).isNotEqualTo(outside)
		assertThat(server.parentSpanContext.isValid).isFalse()
		assertThat(server.text()).noneMatch { it.contains("leak") }

		val resource = server.resource.attributes
		assertThat(resource.get(AttributeKey.stringKey("service.name"))).isEqualTo("dashboard-api")
		assertThat(resource.get(AttributeKey.stringKey("deployment.environment.name"))).isEqualTo("test")
		assertThat(resource.get(AttributeKey.stringKey("k8s.cluster.name"))).isEqualTo("test-cluster")
		assertThat(resource.get(AttributeKey.stringKey("k8s.pod.name"))).isEqualTo("dashboard-api-0")
	}

	@Test
	@DisplayName("health 요청은 span 을 만들지 않지만 http.server.requests 지표는 남는다")
	fun healthIsNotTracedButMeasured() {
		assertThat(http.send("/api/v1/healthz").statusCode()).isEqualTo(200)
		assertThat(TestSpans.await(Duration.ofMillis(500)) { false }.filter { it.kind == SpanKind.SERVER }).isEmpty()
		assertThat(DashboardHttp(managementPort).send("/actuator/prometheus").body())
			.containsPattern("""http_server_requests_seconds_count\{[^}]*uri="/api/v1/healthz"""")
	}

	@Test
	@DisplayName("span 안의 로그에만 trace_id·span_id 가 붙고 요청 ID 는 그 앞에 그대로 있다")
	fun logsCarryTraceIdsInsideSpans(output: CapturedOutput) {
		val response = http.send("/api/v1/organizations/${UUID.randomUUID()}/alerts", headers = mapOf("X-Request-Id" to "req-trace-log"))
		assertThat(response.statusCode()).isEqualTo(401)
		val server = serverSpan(TestSpans.await { spans -> spans.any { it.kind == SpanKind.SERVER } })

		val completed = output.all.lines().single { it.contains("event=platform_trace_completed") && it.contains("req-trace-log") }
		assertThat(completed).contains("[req-trace-log] trace_id=${server.traceId} span_id=${server.spanId} ")
		assertThat(completed).contains("status=401 outcome=client_error")
		assertThat(output.all).doesNotContain("trace_id= span_id=")
	}
}
