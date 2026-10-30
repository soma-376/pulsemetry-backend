package com.team376.pulsemetry.telemetry.adapter.observation

import com.team376.pulsemetry.telemetry.adapter.observation.codex.CodexProfile
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

/** 계약 불일치는 저장 거부와 다르고, 이름·버전·본문이 동적 label로 새지 않는다. */
class UsageContractDiagnosticsTest {
	private val time = 1_788_192_000_000_000_000L
	private val pipeline = ObservationPipeline(ObservationNormalizer(ProfileRegistry(listOf(CodexProfile))))
	private fun text(key: String, value: String): KeyValue = KeyValue.newBuilder().setKey(key)
		.setValue(AnyValue.newBuilder().setStringValue(value)).build()
	private fun number(key: String, value: Long): KeyValue = KeyValue.newBuilder().setKey(key)
		.setValue(AnyValue.newBuilder().setIntValue(value)).build()
	private fun record(): LogRecord = LogRecord.newBuilder().setTimeUnixNano(time).addAllAttributes(listOf(
		text("event.name", "codex.sse_event"), text("event.kind", "response.completed"),
		text("input_token_count", "100"), text("output_token_count", "20"), number("cached_token_count", 40),
		number("cache_write_token_count", 10), text("model", "unknown-CANARY-model"),
	)).build()
	private fun edit(vararg removals: String, addition: KeyValue? = null): LogRecord = record().toBuilder().clearAttributes()
		.addAllAttributes(record().attributesList.filter { it.key !in removals })
		.apply { if (addition != null) addAttributes(addition) }.build()
	private fun run(vararg records: LogRecord, service: String = "codex-app-server"): ObservationBatch {
		val request = ExportLogsServiceRequest.newBuilder().addResourceLogs(ResourceLogs.newBuilder()
			.setResource(Resource.newBuilder().addAttributes(text("service.name", service)).addAttributes(text("service.version", "CANARY-future-version")))
			.addScopeLogs(ScopeLogs.newBuilder().addAllLogRecords(records.toList()))).build()
		return pipeline.run(request, ObservationContext("CANARY-tenant", "CANARY-installation", EpochNanos(time + 10), "masking-v2", "stamp-v1", null))
	}

	@Test
	fun validMissingInvalidAndChangedUsageBranchesHaveDistinctBoundedOutcomes() {
		val batch = run(
			record(),
			edit("cache_write_token_count"),
			edit("cached_token_count", addition = text("cached_token_count", "40")),
			edit("event.kind", addition = text("event.kind", "CANARY-new-completion-kind")),
		)
		assertThat(batch.stats.usageContractResults).isEqualTo(UsageContractOutcome.entries.associate { UsageContractResult(Product.CODEX, it) to 1 })
		assertThat(batch.events).hasSize(4)
		assertThat(batch.stats.rejectedCount).isZero()
		assertThat(batch.events.first().costEstimatedUsd).isNull() // 모르는 모델은 계약 오류나 가짜 금액이 아니다.
		assertThat(batch.stats.usageContractResults.keys.toString()).doesNotContain("CANARY")
	}

	@Test
	fun errorAndUsageConflictIsInvalidButNormalStreamProgressIsNotUsage() {
		val conflict = record().toBuilder().addAttributes(text("error.message", "CANARY-private-error")).build()
		val progress = LogRecord.newBuilder().setTimeUnixNano(time)
			.addAttributes(text("event.name", "codex.sse_event")).addAttributes(text("event.kind", "response.in_progress")).build()
		val batch = run(conflict, progress)
		assertThat(batch.stats.usageContractResults).containsExactlyEntriesOf(mapOf(UsageContractResult(Product.CODEX, UsageContractOutcome.INVALID) to 1))
		assertThat(batch.stats.rejectedCount).isZero()
		assertThat(batch.events).allSatisfy { assertThat(it.costEstimatedUsd).isNull() }
	}

	@Test
	fun completedWithoutReportedTokenComponentsIsIncompleteAndDoesNotInventUsage() {
		val missing = edit("input_token_count", "output_token_count", "cached_token_count", "cache_write_token_count")
		val batch = run(missing)
		assertThat(batch.stats.usageContractResults).containsExactlyEntriesOf(mapOf(UsageContractResult(Product.CODEX, UsageContractOutcome.INCOMPLETE) to 1))
		assertThat(batch.events.single().envelope.usageRole).isEqualTo(UsageRole.NONE)
		assertThat(batch.events.single().tokensTotalDerived).isNull()
		assertThat(batch.events.single().costEstimatedUsd).isNull()
	}

	@Test
	fun unknownServicesAndUnregisteredNamesDoNotBecomeUsageCandidates() {
		assertThat(run(record(), service = "CANARY-unregistered-service").stats.usageContractResults).isEmpty()
		assertThat(run(edit("event.name", addition = text("event.name", "CANARY-new-event"))).stats.usageContractResults).isEmpty()
	}
}
