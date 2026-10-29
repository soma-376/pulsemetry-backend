package com.team376.pulsemetry.telemetry.adapter.observation

import com.team376.pulsemetry.telemetry.adapter.observation.claudecode.ClaudeApiProfile
import com.team376.pulsemetry.telemetry.adapter.observation.codex.CodexCompletedProfile
import com.team376.pulsemetry.telemetry.adapter.observation.pricing.CurrentPublicPrices
import com.team376.pulsemetry.telemetry.adapter.observation.profile.ProfileRegistry
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.logs.v1.LogRecord
import io.opentelemetry.proto.logs.v1.ResourceLogs
import io.opentelemetry.proto.logs.v1.ScopeLogs
import io.opentelemetry.proto.resource.v1.Resource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/** 실제 원문을 fixture에 복사하지 않고 공식 성분 산식·지원 경계를 독립 정수 예제로 검증한다. */
class SeptemberProducerProfilesTest {

	private val time = 1_788_192_000_123_456_789L
	private val pipeline = ObservationPipeline(
		ObservationNormalizer(ProfileRegistry(listOf(ClaudeApiProfile, CodexCompletedProfile))),
		com.team376.pulsemetry.telemetry.adapter.observation.pricing.PricingStage(),
	)
	private fun text(key: String, value: String): KeyValue =
		KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setStringValue(value)).build()
	private fun number(key: String, value: Long): KeyValue =
		KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setIntValue(value)).build()
	private fun run(version: String, record: LogRecord, service: String): EventObservation {
		val resource = Resource.newBuilder().addAttributes(text("service.name", service)).addAttributes(text("service.version", version))
		val request = ExportLogsServiceRequest.newBuilder().addResourceLogs(
			ResourceLogs.newBuilder().setResource(resource).addScopeLogs(ScopeLogs.newBuilder().addLogRecords(record)),
		).build()
		return pipeline.run(request, ObservationContext("tenant", "installation", EpochNanos(time + 10), "masking-v2", "stamp-v1", null)).events.single()
	}
	private fun claude(name: String = "api_request"): LogRecord = LogRecord.newBuilder().setTimeUnixNano(time)
		.setBody(AnyValue.newBuilder().setStringValue("claude_code.$name"))
		.addAllAttributes(listOf(
			text("event.name", name), text("model", "claude-opus-5-5"), text("session.id", "synthetic-session"),
			number("input_tokens", 100), number("output_tokens", 20), number("cache_read_tokens", 400), number("cache_creation_tokens", 10),
		)).build()
	private fun codex(emitter: String, create: Boolean = true): LogRecord = LogRecord.newBuilder().setTimeUnixNano(time).setEventName(emitter)
		.addAllAttributes(listOf(
			text("event.name", "codex.sse_event"), text("event.kind", "response.completed"), text("model", "gpt-6-astra"),
			text("input_token_count", "100"), text("output_token_count", "20"), number("cached_token_count", 40),
			number("reasoning_token_count", 15), text("tool_token_count", "120"),
		)).apply { if (create) addAttributes(number("cache_write_token_count", 10)) }.build()

	@ParameterizedTest
	@ValueSource(strings = ["2.1.283", "2.1.284", "2.1.285"])
	fun claudeSeparatesCachedInputWithoutChangingRawCounts(version: String) {
		val result = run(version, claude(), "claude-code")
		assertThat(result.envelope.usageRole).isEqualTo(UsageRole.PRIMARY)
		assertThat(result.envelope.sourceTime.value).isEqualTo(time)
		assertThat(result.tokensInput).isEqualTo(100)
		assertThat(result.tokensCacheRead).isEqualTo(400)
		assertThat(result.tokensCacheCreate).isEqualTo(10)
		assertThat(result.tokensInputUncached).isEqualTo(100)
		assertThat(result.tokensTotalDerived).isEqualTo(530)
		assertThat(result.costEstimatedUsd).isEqualByComparingTo("0.000930")
	}

	@ParameterizedTest
	@CsvSource("0.158.0-alpha.2.1,1035", "0.159.2,1103")
	fun codexCountsInputAndOutputExactlyOnce(version: String, line: Int) {
		val result = run(version, codex("event otel/src/events/session_telemetry.rs:$line"), "codex-app-server")
		assertThat(result.envelope.usageRole).isEqualTo(UsageRole.PRIMARY)
		assertThat(result.envelope.sourceTime.value).isEqualTo(time)
		assertThat(result.tokensInput).isEqualTo(100)
		assertThat(result.tokensInputUncached).isEqualTo(50)
		assertThat(result.tokensTotalDerived).isEqualTo(120)
		assertThat(result.tokensTotalReported).isEqualTo(120)
		assertThat(result.tokensReasoning).isEqualTo(15)
		assertThat(result.tokensTool).isNull()
		assertThat(result.costEstimatedUsd).isEqualByComparingTo("0.001665")
	}

	@ParameterizedTest
	@CsvSource("0.158.0-alpha.2.1,1035", "0.159.2,1103")
	fun codexMissingWriteIsNotInventedAsZero(version: String, line: Int) {
		val result = run(version, codex("event otel/src/events/session_telemetry.rs:$line", create = false), "codex-app-server")
		assertThat(result.tokensCacheCreate).isNull()
		assertThat(result.tokensInputUncached).isNull()
		assertThat(result.tokensTotalDerived).isEqualTo(120)
		assertThat(result.costEstimatedUsd).isNull()
	}

	@ParameterizedTest
	@CsvSource("0.158.0-alpha.2.1,1035", "0.159.2,1103")
	fun supportDoesNotExpandToOtherEmittersSurfacesOrErrorRows(version: String, line: Int) {
		val emitter = "event otel/src/events/session_telemetry.rs:$line"
		val wrongEmitter = run(version, codex("event otel/src/events/session_telemetry.rs:9999"), "codex-app-server")
		val cli = run(version, codex(emitter), "codex_cli_rs")
		for (result in listOf(wrongEmitter, cli)) {
			assertThat(result.envelope.mappingStatus).isEqualTo(MappingStatus.GENERIC)
			assertThat(result.envelope.usageRole).isEqualTo(UsageRole.NONE)
			assertThat(result.costEstimatedUsd).isNull()
		}
		val conflicting = codex(emitter).toBuilder().addAttributes(text("error.message", "synthetic-error")).build()
		val result = run(version, conflicting, "codex-app-server")
		assertThat(result.envelope.mappingStatus).isEqualTo(MappingStatus.AMBIGUOUS)
		assertThat(result.envelope.usageRole).isEqualTo(UsageRole.DIAGNOSTIC)
		assertThat(result.costEstimatedUsd).isNull()
	}

	@Test
	fun unrelatedSignalsAndVersionsStayUnverified() {
		assertThat(ClaudeApiProfile.spans).isNull()
		assertThat(ClaudeApiProfile.metrics).isNull()
		assertThat(CodexCompletedProfile.spans).isNull()
		assertThat(CodexCompletedProfile.metrics).isNull()
		assertThat(run("2.1.285", claude("subagent_completed"), "claude-code").envelope.usageRole).isEqualTo(UsageRole.NONE)
		assertThat(run("2.1.286", claude(), "claude-code").envelope.mappingStatus).isEqualTo(MappingStatus.GENERIC)
	}
}
