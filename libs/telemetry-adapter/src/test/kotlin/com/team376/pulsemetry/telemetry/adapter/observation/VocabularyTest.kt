package com.team376.pulsemetry.telemetry.adapter.observation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 분류 어휘의 wire 값이 ADR 0020 부록 A 의 표와 같은지 본다. 기대값은 ADR 의 표에서 옮겼다. */
class VocabularyTest {

	@Test
	@DisplayName("공통 상태 어휘 — ADR 0020 부록 A.1")
	fun commonStatusVocabulary() {
		assertThat(wires<RecordStatus>()).containsExactly("active", "excluded")
		assertThat(wires<MappingStatus>()).containsExactly("mapped", "generic", "ambiguous", "invalid")
		assertThat(wires<SourceIdentityKind>()).containsExactly("native_id", "fingerprint")
		assertThat(wires<SourceTimeOrigin>()).containsExactly("otlp_event", "otlp_observed", "span_start", "metric_point")
		assertThat(wires<ObservationSignal>()).containsExactly("log", "span", "metric")
		assertThat(wires<Product>()).containsExactly("claude_code", "codex", "unknown")
		assertThat(wires<Surface>()).containsExactly("cli", "desktop", "app_server", "unknown")
	}

	@Test
	@DisplayName("사용량 분류는 셋으로 나뉘고 섞이지 않는다 — usage_role / usage_scope / workload_kind")
	fun usageVocabulary() {
		assertThat(wires<UsageRole>()).containsExactly("primary", "diagnostic", "none")
		assertThat(wires<UsageScope>()).containsExactly("response", "turn", "interval", "unknown")
		assertThat(wires<WorkloadKind>()).containsExactly("main", "subagent", "compaction", "memory", "guardian", "unknown")
		assertThat(wires<InputSemantics>()).containsExactly("inclusive_cache", "exclusive_cache", "unknown")
		assertThat(wires<OutputSemantics>()).containsExactly("inclusive_reasoning_tool", "exclusive_reasoning_tool", "unknown")
		assertThat(wires<ReportedCostBasis>()).containsExactly("estimate", "billed", "unknown")
	}

	@Test
	@DisplayName("이벤트 쪽 분류 — operation · ttft_scope · span · tool · decision")
	fun eventVocabulary() {
		assertThat(wires<Operation>()).containsExactly("models_list", "responses", "unknown", "none")
		assertThat(wires<TtftScope>()).containsExactly("request", "turn", "unknown", "none")
		assertThat(wires<SpanKind>()).containsExactly("internal", "server", "client", "producer", "consumer", "unspecified", "none")
		assertThat(wires<SpanStatusCode>()).containsExactly("unset", "ok", "error", "none")
		assertThat(wires<ToolOrigin>()).containsExactly("builtin", "mcp", "agent", "unknown", "none")
		assertThat(wires<ToolAction>()).containsExactly("read", "write", "edit", "search", "exec", "fetch", "unknown", "none")
		assertThat(wires<Decision>()).containsExactly("accept", "reject", "abort", "unknown", "none")
		assertThat(DecisionSource.CONFIG.wire).isEqualTo("config")
		assertThat(DecisionSource.AUTOMATED_REVIEWER.wire).isEqualTo("automated_reviewer")
		assertThat(listOf(DecisionScope.NONE, DecisionScope.UNKNOWN).map { it.wire }).containsExactly("none", "unknown")
		assertThat(listOf(ErrorType.NONE, StopReason.NONE, ReasoningEffort.NONE).map { it.wire }).containsOnly("none")
	}

	@Test
	@DisplayName("event_type — 로그 29 · 스팬 7, 부록 A.2·A.4 의 이름 그대로")
	fun eventTypes() {
		assertThat(wires<EventType>()).hasSize(36).doesNotHaveDuplicates()
		assertThat(wires<EventType>()).contains(
			"model.response.usage", "transport.request.attempt", "transport.stream.error", "transport.stream.event",
			"prompt.submitted", "tool.result", "tool.decision", "turn.first_token", "vendor.unknown", "diagnostic",
			"turn", "model.request", "tool", "tool.execution", "tool.wait", "hook", "context.compaction",
		)
	}

	@Test
	@DisplayName("메트릭 어휘 — metric_type · temporality · metric_family · token_component · series·structure")
	fun metricVocabulary() {
		assertThat(wires<MetricType>()).containsExactly("gauge", "sum", "histogram", "exponential_histogram", "summary")
		assertThat(wires<Temporality>()).containsExactly("delta", "cumulative", "unspecified", "none")
		assertThat(wires<TokenComponent>())
			.containsExactly("input", "output", "cache_read", "cache_create", "reasoning", "tool", "unknown", "none")
		assertThat(wires<SeriesIdentityStatus>()).containsExactly("verified", "unverified", "ambiguous", "missing")
		assertThat(wires<StructuralStatus>()).containsExactly("valid", "invalid")
		assertThat(wires<MetricFamily>()).hasSize(17).doesNotHaveDuplicates().contains("token.usage", "vendor.metric")
	}

	@Test
	@DisplayName("품질 플래그 — 부록 A.6 의 열넷")
	fun qualityFlags() {
		assertThat(wires<QualityFlag>()).containsExactlyInAnyOrder(
			"archive_receipt_missing", "ambiguous_attribute", "ambiguous_payload", "invalid_measurement",
			"derived_value_invalid", "usage_semantics_unverified", "cost_conflict", "non_finite_value",
			"provider_unresolved", "event_time_mismatch", "invalid_relation_id", "identity_material_masked",
			"member_unresolved", "multi_team_membership",
		)
	}

	@Test
	@DisplayName("wire 로 상수를 되찾는다 — 없으면 null")
	fun lookupByWire() {
		assertThat(wireValueOf<UsageRole>("primary")).isEqualTo(UsageRole.PRIMARY)
		assertThat(wireValueOf<EventType>("model.response.usage")).isEqualTo(EventType.MODEL_RESPONSE_USAGE)
		assertThat(wireValueOf<UsageRole>("PRIMARY")).isNull()
	}

	private inline fun <reified E> wires(): List<String> where E : Enum<E>, E : WireValue =
		enumValues<E>().map { it.wire }
}
