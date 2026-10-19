package com.team376.pulsemetry.telemetry.adapter.observation

import com.team376.pulsemetry.telemetry.adapter.observation.codex.CodexCompletedProfile
import com.team376.pulsemetry.telemetry.adapter.observation.codex.CodexDesktopCompletedProfile
import com.team376.pulsemetry.telemetry.adapter.observation.codex.CodexProfile
import com.team376.pulsemetry.telemetry.adapter.observation.fixture.FixtureSuite
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

/** ADR 0064의 좁은 Desktop 범위: 명세의 독립 정수 산식과 이미 익명화된 저장소 실캡처를 대조한다. */
class CodexDesktopCompletedProfileTest {

	private val time = 1_788_192_000_123_456_789L
	private val emitter = "event otel/src/events/session_telemetry.rs:1012"
	private val context = ObservationContext("tenant", "installation", EpochNanos(time + 10), "masking-v2", "stamp-v1", null)
	private val registry = ProfileRegistry(listOf(CodexProfile, CodexCompletedProfile, CodexDesktopCompletedProfile))
	private val pipeline = ObservationPipeline(ObservationNormalizer(registry), CurrentPublicPrices.select(CurrentPublicPrices.VERSION))
	private fun text(key: String, value: String): KeyValue =
		KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setStringValue(value)).build()
	private fun number(key: String, value: Long): KeyValue =
		KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setIntValue(value)).build()
	private fun record(location: String = emitter, create: Boolean = true, event: String = "codex.sse_event", kind: String = "response.completed"): LogRecord =
		LogRecord.newBuilder().setTimeUnixNano(time).setEventName(location).addAllAttributes(listOf(
			text("event.name", event), text("event.kind", kind), text("model", "gpt-6-astra"), text("conversation.id", "synthetic-session"),
			text("input_token_count", "100"), text("output_token_count", "20"), number("cached_token_count", 40),
			number("reasoning_token_count", 15), text("tool_token_count", "120"),
		)).apply { if (create) addAttributes(number("cache_write_token_count", 10)) }.build()
	private fun request(version: String, record: LogRecord): ExportLogsServiceRequest = ExportLogsServiceRequest.newBuilder()
		.addResourceLogs(ResourceLogs.newBuilder().setResource(Resource.newBuilder()
			.addAttributes(text("service.name", "Codex Desktop")).addAttributes(text("service.version", version)))
			.addScopeLogs(ScopeLogs.newBuilder().addLogRecords(record))).build()
	private fun run(version: String, record: LogRecord = record()): EventObservation = pipeline.run(request(version, record), context).events.single()

	@ParameterizedTest
	@ValueSource(strings = ["0.153.4", "0.154.0-alpha.6.2"])
	fun confirmedDesktopCompletionUsesNonOverlappingTokensAndKeepsIdentity(version: String) {
		val input = request(version, record())
		val before = ObservationPipeline(ObservationNormalizer(ProfileRegistry.EMPTY)).run(input, context).events.single()
		val result = pipeline.run(input, context).events.single()
		assertThat(result.envelope.observationId).isEqualTo(before.envelope.observationId)
		assertThat(result.envelope.sourceTime.value).isEqualTo(time)
		assertThat(result.envelope.surface).isEqualTo(Surface.DESKTOP)
		assertThat(result.envelope.mappingVersion).isEqualTo("codex-desktop-completed-v1")
		assertThat(result.envelope.mappingStatus).isEqualTo(MappingStatus.MAPPED)
		assertThat(result.envelope.usageRole).isEqualTo(UsageRole.PRIMARY)
		assertThat(result.semanticsProfile).isEqualTo("codex-responses-inclusive-v1")
		assertThat(result.tokensInput).isEqualTo(100)
		assertThat(result.tokensOutput).isEqualTo(20)
		assertThat(result.tokensCacheRead).isEqualTo(40)
		assertThat(result.tokensCacheCreate).isEqualTo(10)
		assertThat(result.tokensInputUncached).isEqualTo(50)
		assertThat(result.tokensTotalDerived).isEqualTo(120)
		assertThat(result.tokensTotalReported).isEqualTo(120)
		assertThat(result.tokensReasoning).isEqualTo(15)
		assertThat(result.tokensTool).isNull()
		assertThat(result.costEstimatedUsd).isEqualByComparingTo("0.001665")
		assertThat(result.pricingVersion).isEqualTo("public-standard-global-reference-2026-10-16-v4")
	}

	@ParameterizedTest
	@ValueSource(strings = ["0.155.0-alpha.2.6", "0.155.0-alpha.9.2", "0.158.0-alpha.2.1", "0.159.2", "0.162.0-alpha.17.2", "0.154.0-alpha.6.3"])
	fun otherDesktopVersionsStayGeneric(version: String) {
		assertGeneric(run(version))
	}

	@ParameterizedTest
	@ValueSource(strings = ["event otel/src/events/session_telemetry.rs:999", "event otel/src/events/session_telemetry.rs:1035", ""])
	fun unconfirmedDesktopEmittersStayGeneric(location: String) {
		for (version in listOf("0.153.4", "0.154.0-alpha.6.2")) assertGeneric(run(version, record(location)))
	}

	@ParameterizedTest
	@ValueSource(strings = ["0.153.4", "0.154.0-alpha.6.2"])
	fun unrelatedEventsErrorsAndMissingComponentsAreNotInvented(version: String) {
		assertGeneric(run(version, record(event = "codex.conversation_starts")))
		assertGeneric(run(version, record(kind = "response.in_progress")))
		val conflicting = run(version, record().toBuilder().addAttributes(text("error.message", "synthetic-error")).build())
		assertThat(conflicting.envelope.mappingStatus).isEqualTo(MappingStatus.AMBIGUOUS)
		assertThat(conflicting.envelope.usageRole).isEqualTo(UsageRole.DIAGNOSTIC)
		assertThat(conflicting.costEstimatedUsd).isNull()
		val missing = run(version, record(create = false))
		assertThat(missing.envelope.usageRole).isEqualTo(UsageRole.PRIMARY)
		assertThat(missing.tokensCacheCreate).isNull()
		assertThat(missing.tokensInputUncached).isNull()
		assertThat(missing.costEstimatedUsd).isNull()
	}

	@ParameterizedTest
	@CsvSource("0.153.4,2", "0.154.0-alpha.6.2,3")
	fun existingSanitizedDesktopCapturesMatchTheNarrowCompletionProfile(version: String, expectedCount: Int) {
		val suite = FixtureSuite(registry, CurrentPublicPrices.select(CurrentPublicPrices.VERSION))
		val case = suite.inputs("codex/real").single { it.name.endsWith("logs-codex-desktop-$version-sse") }
		assertThat(suite.checkInvariants(case)).isEmpty()
		val events = case.documents.flatMap { pipeline.run(suite.request(it), context).events }
		assertThat(events).hasSize(expectedCount)
		assertThat(events).allSatisfy {
			assertThat(it.envelope.usageRole).isEqualTo(UsageRole.PRIMARY)
			assertThat(it.envelope.mappingStatus).isEqualTo(MappingStatus.MAPPED)
			assertThat(it.semanticsProfile).isEqualTo("codex-responses-inclusive-v1")
		}
	}

	@Test
	fun onlyLogsAreRegistered() {
		assertThat(CodexDesktopCompletedProfile.spans).isNull()
		assertThat(CodexDesktopCompletedProfile.metrics).isNull()
		assertThat(CodexDesktopCompletedProfile.logs.allowlist.record).doesNotContain("prompt", "user.email", "error.message")
	}

	private fun assertGeneric(result: EventObservation) {
		assertThat(result.envelope.mappingStatus).isEqualTo(MappingStatus.GENERIC)
		assertThat(result.envelope.usageRole).isEqualTo(UsageRole.NONE)
		assertThat(result.semanticsProfile).isNull()
		assertThat(result.costEstimatedUsd).isNull()
	}
}
