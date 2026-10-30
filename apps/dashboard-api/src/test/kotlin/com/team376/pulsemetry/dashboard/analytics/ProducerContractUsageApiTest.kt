package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.authentication.DashboardPrincipal
import com.team376.pulsemetry.dashboard.authentication.Role
import com.team376.pulsemetry.dashboard.support.AbstractDashboardApiTest
import com.team376.pulsemetry.dashboard.support.DashboardHttp
import com.team376.pulsemetry.dashboard.support.DashboardTestStores
import com.team376.pulsemetry.dashboard.support.SourceFixtures
import com.team376.pulsemetry.dashboard.support.TestDashboardAuthenticator
import com.team376.pulsemetry.persistence.telemetry.AnalysisWriteBoundary
import com.team376.pulsemetry.persistence.telemetry.ClickHouseHttpClient
import com.team376.pulsemetry.persistence.telemetry.TelemetryEventsSink
import com.team376.pulsemetry.telemetry.adapter.observation.EpochNanos
import com.team376.pulsemetry.telemetry.adapter.observation.ObservationContext
import com.team376.pulsemetry.telemetry.adapter.observation.ObservationNormalizer
import com.team376.pulsemetry.telemetry.adapter.observation.ObservationPipeline
import com.team376.pulsemetry.telemetry.adapter.observation.OrgAttribution
import com.team376.pulsemetry.telemetry.adapter.observation.RowVersioning
import com.team376.pulsemetry.telemetry.adapter.observation.claudecode.ClaudeCodeProfile
import com.team376.pulsemetry.telemetry.adapter.observation.codex.CodexProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.ProfileRegistry
import com.team376.pulsemetry.telemetry.enricher.observation.EnrichedEvent
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.logs.v1.LogRecord
import io.opentelemetry.proto.logs.v1.ResourceLogs
import io.opentelemetry.proto.logs.v1.ScopeLogs
import io.opentelemetry.proto.resource.v1.Resource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** 합성 원본 → 운영 정규화·가격·ClickHouse sink → 실제 대시보드 API를 통과한다(ADR 0074). */
class ProducerContractUsageApiTest : AbstractDashboardApiTest() {
	private val time = Instant.parse("2026-09-09T01:00:00Z")

	@Test
	@DisplayName("미래·누락·비표준 버전의 호환 이벤트는 세션·토큰·비용에 포함되고 재전송은 중복 합산되지 않는다")
	fun compatibleEventsFromNewVersionsReachDashboard() {
		val tenant = DashboardTestStores.insertTenant()
		val member = SourceFixtures.insertMember(tenant, "contract-${UUID.randomUUID()}@example.test")
		SourceFixtures.completeBackfill()
		SourceFixtures.setSummary(tenant, firstReceivedAt = time, firstObservedAt = time, lastReceivedAt = time)
		val request = ExportLogsServiceRequest.newBuilder()
			.addResourceLogs(codex("codex_cli_rs", "2099.1.0-beta.9", "cli"))
			.addResourceLogs(codex("Codex Desktop", null, "desktop"))
			.addResourceLogs(codex("codex-app-server", "custom-build", "app-server"))
			.addResourceLogs(claude("2099.1.0", "claude-future"))
			.addResourceLogs(claude(null, "claude-missing"))
			.build()
		val pipeline = ObservationPipeline(ObservationNormalizer(ProfileRegistry(listOf(CodexProfile, ClaudeCodeProfile))))
		val batch = pipeline.run(request, ObservationContext(tenant.toString(), UUID.randomUUID().toString(), EpochNanos.of(time), "test", "test", null))
		val org = OrgAttribution(member.toString(), null, emptyList(), "{}", "test")
		val events = batch.events.map { EnrichedEvent(it, org) }
		val sink = TelemetryEventsSink(ClickHouseHttpClient(DashboardTestStores.clickHouseUrl()))
		val boundary = AnalysisWriteBoundary(tenant.toString(), null, 0)
		// 동일 원본의 재전송이 있어도 FINAL과 조회 snapshot에는 관측 하나씩만 남는다.
		repeat(2) { assertThat(sink.insert(events, RowVersioning.live(time), boundary)).isEqualTo(5) }
		assertThat(DashboardTestStores.clickHouseAdmin("SELECT count() FROM telemetry_events FINAL WHERE tenant_id = '$tenant' FORMAT TSV").trim()).isEqualTo("5")

		val response = http.send(
			"/api/v1/organizations/$tenant/analytics/overview?startDate=2026-09-07&endDate=2026-09-13&compare=none",
			headers = mapOf("Authorization" to TestDashboardAuthenticator.header(DashboardPrincipal(tenant, member, Role.ADMIN))),
		)
		assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200)
		val body = DashboardHttp.json(response)
		assertThat(body.at("/summary/sessionCount/value").asLong()).isEqualTo(5)
		assertThat(body.at("/summary/activeUsers/value").asLong()).isEqualTo(1)
		// Codex: (포함형 입력1000 + 출력200) ×3. Claude: (입력100 + 출력20 + 캐시40+10) ×2.
		assertThat(body.at("/summary/totalTokens/value").asLong()).isEqualTo(3940)
		// 고정 참조 단가: Codex 0.016650 ×3 + Claude 0.0006495 ×2. reasoning은 출력에 재가산하지 않는다.
		assertThat(BigDecimal(body.at("/summary/equivalentCostUsd/value").asString())).isEqualByComparingTo("0.051249")
	}

	private fun codex(service: String, version: String?, session: String): ResourceLogs = resource(service, version,
		LogRecord.newBuilder().setTimeUnixNano(EpochNanos.of(time).value)
			// emitter 경로·행은 바뀌어도 토큰 계약은 그대로다.
			.setEventName("new_crate::telemetry:9999")
			.addAllAttributes(listOf(
				text("event.name", "codex.sse_event"), text("event.kind", "response.completed"), text("conversation.id", session),
				text("model", "gpt-6-astra"), text("input_token_count", "1000"), text("output_token_count", "200"),
				text("tool_token_count", "1200"), count("cached_token_count", 400), count("cache_write_token_count", 100), count("reasoning_token_count", 150),
			)).build(),
	)

	private fun claude(version: String?, session: String): ResourceLogs = resource("claude-code", version,
		LogRecord.newBuilder().setTimeUnixNano(EpochNanos.of(time).value).setBody(AnyValue.newBuilder().setStringValue("claude_code.api_request"))
			.addAllAttributes(listOf(
				text("event.name", "api_request"), text("session.id", session), text("model", "claude-sonnet-4"),
				count("input_tokens", 100), count("output_tokens", 20), count("cache_read_tokens", 40), count("cache_creation_tokens", 10),
			)).build(),
	)

	private fun resource(service: String, version: String?, record: LogRecord): ResourceLogs {
		val resource = Resource.newBuilder().addAttributes(text("service.name", service))
		version?.let { resource.addAttributes(text("service.version", it)) }
		return ResourceLogs.newBuilder().setResource(resource).addScopeLogs(ScopeLogs.newBuilder().addLogRecords(record)).build()
	}

	private fun text(key: String, value: String): KeyValue = KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setStringValue(value)).build()
	private fun count(key: String, value: Long): KeyValue = KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setIntValue(value)).build()
}
