package com.team376.pulsemetry.telemetry.adapter.observation

import com.team376.pulsemetry.telemetry.adapter.observation.codex.CodexProfile
import com.team376.pulsemetry.telemetry.adapter.observation.fixture.FixtureSuite
import com.team376.pulsemetry.telemetry.adapter.observation.pricing.PricingStage
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
import org.junit.jupiter.params.provider.ValueSource

/** ADR 0074: producer 버전·표면·소스 위치와 독립적인 사용량 계약을 검증한다. */
class CodexEventContractTest {

	private val time = 1_788_192_000_123_456_789L
	private val emitter = "event otel/src/events/session_telemetry.rs:1132"
	private val context = ObservationContext("tenant", "installation", EpochNanos(time + 10), "masking-v2", "stamp-v1", null)
	private val registry = ProfileRegistry(listOf(CodexProfile))
	private val pipeline = ObservationPipeline(ObservationNormalizer(registry), PricingStage())
	private fun text(key: String, value: String): KeyValue =
		KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setStringValue(value)).build()
	private fun number(key: String, value: Long): KeyValue =
		KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setIntValue(value)).build()
	private fun record(location: String = emitter, event: String = "codex.sse_event", kind: String = "response.completed"): LogRecord =
		LogRecord.newBuilder().setTimeUnixNano(time).setEventName(location).addAllAttributes(listOf(
			text("event.name", event), text("event.kind", kind), text("model", "gpt-6-astra"), text("conversation.id", "synthetic-current-session"),
			text("input_token_count", "1000"), text("output_token_count", "200"), number("cached_token_count", 400),
			number("cache_write_token_count", 100), number("reasoning_token_count", 150), text("tool_token_count", "1200"),
		)).build()
	private fun request(version: String?, record: LogRecord, service: String = "codex-app-server"): ExportLogsServiceRequest =
		ExportLogsServiceRequest.newBuilder().addResourceLogs(ResourceLogs.newBuilder().setResource(Resource.newBuilder()
			.addAttributes(text("service.name", service)).apply { if (version != null) addAttributes(text("service.version", version)) })
			.addScopeLogs(ScopeLogs.newBuilder().addLogRecords(record))).build()
	private fun run(version: String?, record: LogRecord = record(), service: String = "codex-app-server"): EventObservation =
		pipeline.run(request(version, record, service), context).events.single()

	@ParameterizedTest
	@ValueSource(strings = ["0.165.0", "0.166.0", "0.166.0-alpha.5.1"])
	fun exactAppServerCompletionKeepsIdentityAndUsesIndependentTokenAndCostArithmetic(version: String) {
		val input = request(version, record())
		val before = ObservationPipeline(ObservationNormalizer(ProfileRegistry.EMPTY)).run(input, context).events.single()
		val result = pipeline.run(input, context).events.single()
		assertThat(result.envelope.observationId).isEqualTo(before.envelope.observationId)
		assertThat(result.envelope.sourceTime.value).isEqualTo(time)
		assertThat(result.envelope.surface).isEqualTo(Surface.APP_SERVER)
		assertThat(result.envelope.mappingStatus).isEqualTo(MappingStatus.MAPPED)
		assertThat(result.envelope.mappingVersion).isEqualTo("codex-event-contract-v1")
		assertThat(result.envelope.usageRole).isEqualTo(UsageRole.PRIMARY)
		assertThat(result.semanticsProfile).isEqualTo("codex-responses-inclusive-v1")
		assertThat(result.tokensInput).isEqualTo(1000)
		assertThat(result.tokensOutput).isEqualTo(200)
		assertThat(result.tokensCacheRead).isEqualTo(400)
		assertThat(result.tokensCacheCreate).isEqualTo(100)
		assertThat(result.tokensInputUncached).isEqualTo(500)
		assertThat(result.tokensTotalDerived).isEqualTo(1200)
		assertThat(result.tokensTotalReported).isEqualTo(1200)
		assertThat(result.tokensReasoning).isEqualTo(150)
		assertThat(result.tokensTool).isNull()
		// (500×10 + 400×1 + 100×12.5 + 200×50) / 1,000,000.
		assertThat(result.costEstimatedUsd).isEqualByComparingTo("0.016650")
	}

	@ParameterizedTest
	@ValueSource(strings = ["0.165.0", "0.166.0", "0.166.0-alpha.5.1"])
	fun independentFixturesMatchVerifiedWire(version: String) {
		val suite = FixtureSuite(registry, PricingStage())
		val case = suite.load("codex/app-server-current").single { it.name.endsWith("logs-app-server-$version-completed") }
		assertThat(suite.check(case)).isEmpty()
	}

	@Test
	fun existingDesktopFixtureUsesTheSameContract() {
		val suite = FixtureSuite(registry, PricingStage())
		for (case in suite.load("codex/desktop-0.165.0")) {
			assertThat(suite.check(case)).isEmpty()
		}
	}

	@Test
	fun versionSurfaceAndEmitterNeverGateTheSameContract() {
		val services = mapOf("codex_cli_rs" to Surface.CLI, "codex-app-server" to Surface.APP_SERVER, "Codex Desktop" to Surface.DESKTOP)
		for (version in listOf(null, "", "99.0.0", "99.1.0-alpha.123", "custom-build")) {
			for ((service, surface) in services) {
				for (location in listOf("", "arbitrary-source:9999", emitter)) {
					val result = run(version, record(location), service)
					assertThat(result.envelope.mappingStatus).isEqualTo(MappingStatus.MAPPED)
					assertThat(result.envelope.surface).isEqualTo(surface)
					assertThat(result.envelope.serviceVersion).isEqualTo(version)
					assertThat(result.envelope.sessionId).isEqualTo("synthetic-current-session")
					assertThat(result.envelope.usageRole).isEqualTo(UsageRole.PRIMARY)
					assertThat(result.tokensInputUncached).isEqualTo(500)
					assertThat(result.tokensTotalDerived).isEqualTo(1200)
					assertThat(result.costEstimatedUsd).isEqualByComparingTo("0.016650")
				}
			}
		}
	}

	@Test
	fun otherProductsEventsAndIncompleteBranchesAreNotUsage() {
		assertGeneric(run("future", service = "unknown-codex-like-service"))
		assertGeneric(run("future", record(event = "unregistered-event")))
		for (kind in listOf("response.in_progress", "response.failed", "")) {
			val result = run("future", record(kind = kind))
			assertThat(result.envelope.usageRole).isEqualTo(UsageRole.NONE)
			assertThat(result.costEstimatedUsd).isNull()
		}
		val conflicting = run("future", record().toBuilder().addAttributes(text("error.message", "synthetic-error")).build())
		assertThat(conflicting.envelope.mappingStatus).isEqualTo(MappingStatus.AMBIGUOUS)
		assertThat(conflicting.envelope.usageRole).isEqualTo(UsageRole.DIAGNOSTIC)
		assertThat(conflicting.costEstimatedUsd).isNull()
		val alias = run("future", record().toBuilder().addAttributes(text("kind", "response.failed")).build())
		assertThat(alias.envelope.usageRole).isEqualTo(UsageRole.NONE)
		assertThat(alias.costEstimatedUsd).isNull()
	}

	@Test
	fun transportOperationUsesExplicitEndpointNotSourcePosition() {
		for ((endpoint, expected) in mapOf("/responses" to Operation.RESPONSES, "/models" to Operation.MODELS_LIST, "/unknown" to Operation.UNKNOWN)) {
			val result = run(null, record(location = "source:9999", event = "codex.api_request").toBuilder().addAttributes(text("endpoint", endpoint)).build())
			assertThat(result.operation).isEqualTo(expected)
			assertThat(result.envelope.usageRole).isEqualTo(UsageRole.NONE)
		}
		val absent = run("future", record("event model-provider/src/models_endpoint.rs:204", event = "codex.api_request"))
		assertThat(absent.operation).isEqualTo(Operation.UNKNOWN)
	}

	@ParameterizedTest
	@ValueSource(strings = ["0.165.0", "0.166.0", "0.166.0-alpha.5.1"])
	fun missingAndWrongWireComponentsDoNotCreateCost(version: String) {
		for (key in listOf("input_token_count", "output_token_count", "cached_token_count", "cache_write_token_count")) {
			val missing = record().toBuilder().clearAttributes().addAllAttributes(record().attributesList.filter { it.key != key }).build()
			assertThat(run(version, missing).costEstimatedUsd).isNull()
			val wrong = if (key in setOf("input_token_count", "output_token_count")) number(key, 20) else text(key, "20")
			assertThat(run(version, missing.toBuilder().addAttributes(wrong).build()).costEstimatedUsd).isNull()
		}
	}

	@ParameterizedTest
	@ValueSource(strings = ["0.165.0", "0.166.0", "0.166.0-alpha.5.1"])
	fun explicitZeroIsReportedAndUnknownModelsDoNotGetInventedCost(version: String) {
		val base = record().toBuilder().clearAttributes()
			.addAllAttributes(record().attributesList.filter { it.key !in setOf("cache_write_token_count", "reasoning_token_count", "tool_token_count") })
			.addAttributes(number("cache_write_token_count", 0)).build()
		val result = run(version, base)
		assertThat(result.tokensCacheCreate).isZero()
		assertThat(result.tokensReasoning).isNull()
		assertThat(result.tokensTotalReported).isNull()
		assertThat(result.tokensInputUncached).isEqualTo(600)
		assertThat(result.tokensTotalDerived).isEqualTo(1200)
		assertThat(result.costEstimatedUsd).isEqualByComparingTo("0.016400")
		val unknownModel = base.toBuilder().clearAttributes().addAllAttributes(base.attributesList.filter { it.key != "model" })
			.addAttributes(text("model", "unverified-synthetic-model")).build()
		assertThat(run(version, unknownModel).costEstimatedUsd).isNull()
	}

	@Test
	fun metadataStillExcludesSensitiveContent() {
		assertThat(CodexProfile.logs.allowlist.record).doesNotContain("user.email", "user.account_id", "prompt", "error.message")
	}

	private fun assertGeneric(result: EventObservation) {
		assertThat(result.envelope.mappingStatus).isEqualTo(MappingStatus.GENERIC)
		assertThat(result.envelope.usageRole).isEqualTo(UsageRole.NONE)
		assertThat(result.semanticsProfile).isNull()
		assertThat(result.costEstimatedUsd).isNull()
	}
}
