package com.team376.pulsemetry.telemetry.adapter.observation

import com.team376.pulsemetry.telemetry.adapter.observation.claudecode.ClaudeCodeProfile
import com.team376.pulsemetry.telemetry.adapter.observation.fixture.FixtureSuite
import com.team376.pulsemetry.telemetry.adapter.observation.pricing.PricingStage
import com.team376.pulsemetry.telemetry.adapter.observation.profile.ProfileRegistry
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.logs.v1.LogRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource

/** ADR 0074: producer 버전 대신 알려진 이벤트 계약을 검증하는 독립 합성 사례다. */
class ClaudeEventContractTest {

	private val context = ObservationContext("tenant", "installation", EpochNanos(1_788_192_000_123_456_799L), "masking-v2", "stamp-v1", null)
	private val pipeline = ObservationPipeline(ObservationNormalizer(ProfileRegistry(listOf(ClaudeCodeProfile))), PricingStage())
	private fun fixture(): ExportLogsServiceRequest {
		val suite = FixtureSuite()
		val document = suite.inputs("claude_code/api-2.1.311").single().documents.single()
		return suite.request(document) as ExportLogsServiceRequest
	}
	private fun editRecord(change: (LogRecord.Builder) -> Unit): ExportLogsServiceRequest = fixture().toBuilder().also { request ->
		change(request.getResourceLogsBuilder(0).getScopeLogsBuilder(0).getLogRecordsBuilder(0))
	}.build()
	private fun run(request: ExportLogsServiceRequest = fixture()): EventObservation = pipeline.run(request, context).events.single()
	private fun attribute(key: String, value: AnyValue): KeyValue = KeyValue.newBuilder().setKey(key).setValue(value).build()
	private fun remove(record: LogRecord.Builder, key: String) {
		val kept = record.attributesList.filter { it.key != key }
		record.clearAttributes().addAllAttributes(kept)
	}
	private fun assertGeneric(result: EventObservation) {
		assertThat(result.envelope.mappingStatus).isEqualTo(MappingStatus.GENERIC)
		assertThat(result.envelope.usageRole).isEqualTo(UsageRole.NONE)
		assertThat(result.semanticsProfile).isNull()
		assertThat(result.costEstimatedUsd).isNull()
	}

	@Test
	fun unknownEventAndServiceStayGenericAndProductConflictIsAmbiguous() {
		assertGeneric(run(editRecord { record ->
			remove(record, "event.name")
			record.addAttributes(attribute("event.name", AnyValue.newBuilder().setStringValue("future_unknown_event").build()))
			record.setBody(AnyValue.newBuilder().setStringValue("claude_code.future_unknown_event"))
		}))
		val unknownService = fixture().toBuilder()
		unknownService.getResourceLogsBuilder(0).resourceBuilder.setAttributes(0,
			attribute("service.name", AnyValue.newBuilder().setStringValue("not-claude-code").build()))
		assertGeneric(run(unknownService.build()))
		val conflict = run(editRecord { record ->
			remove(record, "event.name")
			record.addAttributes(attribute("event.name", AnyValue.newBuilder().setStringValue("codex.sse_event").build()))
		})
		assertThat(conflict.envelope.mappingStatus).isEqualTo(MappingStatus.AMBIGUOUS)
		assertThat(conflict.envelope.usageRole).isNotEqualTo(UsageRole.PRIMARY)
		assertThat(conflict.costEstimatedUsd).isNull()
	}

	@Test
	fun confirmedApiRequestKeepsIdentityAndCountsEachTokenComponentOnce() {
		val request = fixture()
		val before = ObservationPipeline(ObservationNormalizer(ProfileRegistry.EMPTY)).run(request, context).events.single()
		val result = run(request)
		assertThat(result.envelope.observationId).isEqualTo(before.envelope.observationId)
		assertThat(result.envelope.mappingStatus).isEqualTo(MappingStatus.MAPPED)
		assertThat(result.envelope.usageRole).isEqualTo(UsageRole.PRIMARY)
		assertThat(result.semanticsProfile).isEqualTo("claude-code-exclusive-v1")
		assertThat(result.tokensInputUncached).isEqualTo(100)
		assertThat(result.tokensTotalDerived).isEqualTo(530)
		assertThat(result.costReportedUsd).isEqualByComparingTo("0.002")
		assertThat(result.costEstimatedUsd).isEqualByComparingTo("0.000930")
		assertThat(result.durationNs).isEqualTo(1_000_000_000L)
		assertThat(result.ttftNs).isEqualTo(250_000_000L)
		assertThat(result.envelope.mappingVersion).isEqualTo("claude-code-event-contract-v1")
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = ["2.1.280", "2.1.310", "2.1.314", "999.0.0-alpha.1", "vendor-build/custom", ""])
	fun sameEventContractIsIndependentOfProducerVersion(version: String?) {
		val request = fixture().toBuilder()
		val resource = request.getResourceLogsBuilder(0).resourceBuilder
		resource.clearAttributes().addAttributes(attribute("service.name", AnyValue.newBuilder().setStringValue("claude-code").build()))
		if (version != null) resource.addAttributes(attribute("service.version", AnyValue.newBuilder().setStringValue(version).build()))
		val result = run(request.build())
		assertThat(result.envelope.mappingStatus).isEqualTo(MappingStatus.MAPPED)
		assertThat(result.envelope.usageRole).isEqualTo(UsageRole.PRIMARY)
		assertThat(result.semanticsProfile).isEqualTo("claude-code-exclusive-v1")
		assertThat(result.tokensTotalDerived).isEqualTo(530)
		assertThat(result.costEstimatedUsd).isEqualByComparingTo("0.000930")
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = ["2.1.311", "999.0.0-alpha.1", "vendor-build/custom"])
	fun spansAndMetricsRemainSupplementaryAcrossVersions(version: String?) {
		val suite = FixtureSuite()
		val cases = suite.inputs("claude_code/synthetic")
		val spans = (suite.request(cases.single { it.name.endsWith("/spans") }.documents.single()) as ExportTraceServiceRequest).toBuilder()
		for (resource in spans.resourceSpansBuilderList) {
			val kept = resource.resource.attributesList.filter { it.key != "service.version" }
			resource.resourceBuilder.clearAttributes().addAllAttributes(kept)
			if (version != null) resource.resourceBuilder.addAttributes(attribute("service.version", AnyValue.newBuilder().setStringValue(version).build()))
		}
		val events = pipeline.run(spans.build(), context).events
		assertThat(events).hasSize(7)
		assertThat(events).allSatisfy { assertThat(it.envelope.usageRole).isNotEqualTo(UsageRole.PRIMARY) }
		assertThat(events.single { it.eventType == EventType.MODEL_REQUEST }.envelope.usageRole).isEqualTo(UsageRole.DIAGNOSTIC)
		val metrics = (suite.request(cases.single { it.name.endsWith("/metrics") }.documents.single()) as ExportMetricsServiceRequest).toBuilder()
		for (resource in metrics.resourceMetricsBuilderList) {
			val kept = resource.resource.attributesList.filter { it.key != "service.version" }
			resource.resourceBuilder.clearAttributes().addAllAttributes(kept)
			if (version != null) resource.resourceBuilder.addAttributes(attribute("service.version", AnyValue.newBuilder().setStringValue(version).build()))
		}
		val points = pipeline.run(metrics.build(), context).metricPoints
		assertThat(points).hasSize(12)
		assertThat(points).allSatisfy { assertThat(it.envelope.usageRole).isNotEqualTo(UsageRole.PRIMARY) }
		assertThat(points.filter { it.metricFamily == MetricFamily.TOKEN_USAGE }).hasSize(4).allSatisfy {
			assertThat(it.envelope.usageRole).isEqualTo(UsageRole.DIAGNOSTIC)
		}
	}

	@ParameterizedTest
	@ValueSource(strings = ["user_prompt", "api_response_body", "subagent_completed"])
	fun otherKnownEventsDoNotCountAsUsage(name: String) {
		val result = run(editRecord { record ->
			remove(record, "event.name")
			record.addAttributes(attribute("event.name", AnyValue.newBuilder().setStringValue(name).build()))
			record.setBody(AnyValue.newBuilder().setStringValue("claude_code.$name"))
		})
		assertThat(result.envelope.mappingStatus).isEqualTo(MappingStatus.MAPPED)
		assertThat(result.envelope.usageRole).isNotEqualTo(UsageRole.PRIMARY)
		assertThat(result.tokensTotalDerived).isNull()
		assertThat(result.costEstimatedUsd).isNull()
	}

	@ParameterizedTest
	@ValueSource(strings = ["input_tokens", "output_tokens", "cache_read_tokens", "cache_creation_tokens"])
	fun missingWrongWireOrNegativeCountsCannotCreateTotalOrPrice(key: String) {
		for (replacement in listOf(null, AnyValue.newBuilder().setStringValue("10").build(), AnyValue.newBuilder().setIntValue(-1).build())) {
			val result = run(editRecord { record ->
				remove(record, key)
				if (replacement != null) record.addAttributes(attribute(key, replacement))
			})
			assertThat(result.tokensTotalDerived).isNull()
			assertThat(result.costEstimatedUsd).isNull()
		}
	}

	@Test
	fun explicitZeroIsKnownUsageAndConflictingBodyStaysAmbiguous() {
		val zero = run(editRecord { record ->
			for (key in listOf("input_tokens", "output_tokens", "cache_read_tokens", "cache_creation_tokens")) {
				remove(record, key)
				record.addAttributes(attribute(key, AnyValue.newBuilder().setIntValue(0).build()))
			}
		})
		assertThat(zero.envelope.usageRole).isEqualTo(UsageRole.PRIMARY)
		assertThat(zero.tokensTotalDerived).isZero()
		assertThat(zero.costEstimatedUsd).isEqualByComparingTo("0")
		val conflict = run(editRecord { it.setBody(AnyValue.newBuilder().setStringValue("claude_code.api_error")) })
		assertThat(conflict.envelope.mappingStatus).isEqualTo(MappingStatus.AMBIGUOUS)
		assertThat(conflict.costEstimatedUsd).isNull()
	}
}
