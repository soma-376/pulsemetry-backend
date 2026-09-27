package com.team376.pulsemetry.telemetry.adapter.observation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor

/**
 * 관측 타입의 필드 집합이 두 분석 테이블의 DDL 과 1:1 인지 본다 (ADR 0020 §1 · 부록 D).
 *
 * 기대값은 DDL 의 컬럼 이름 목록을 **테스트 상수로 옮긴 것**이다 — 이 모듈은 적재 모듈의 SQL 을 읽을 수
 * 없다. 속성 이름(camelCase)을 snake_case 로 바꿔 대조한다. 봉투는 세 조각(정규화 몫·조직 보강·적재)으로
 * 나뉘어 있으므로 셋을 합친 집합이 봉투와 같아야 하고, 정규화 몫의 순서는 DDL 순서를 따른다.
 */
class ObservationColumnsTest {

	@Test
	@DisplayName("봉투 49컬럼 = 정규화 몫 42 + 조직 보강 5 + 적재 2 — 누락·잉여 없음")
	fun envelopeIsCoveredExactlyOnce() {
		val envelope = EVENTS_DDL.take(ENVELOPE_SIZE)
		val parts = columnsOf(ObservationEnvelope::class) + columnsOf(OrgAttribution::class) + columnsOf(RowVersioning::class)

		assertThat(parts).doesNotHaveDuplicates()
		assertThat(parts).containsExactlyInAnyOrderElementsOf(envelope)
		assertThat(columnsOf(ObservationEnvelope::class)).hasSize(42)
		// 정규화 몫은 DDL 순서를 그대로 따른다.
		assertThat(columnsOf(ObservationEnvelope::class))
			.containsExactlyElementsOf(envelope.filter { it in columnsOf(ObservationEnvelope::class) })
		assertThat(columnsOf(OrgAttribution::class))
			.containsExactly("member_id", "team_id_as_of", "team_ids_as_of", "enrichment_json", "enrichment_version")
		assertThat(columnsOf(RowVersioning::class)).containsExactly("normalizer_rev", "row_version")
	}

	@Test
	@DisplayName("두 테이블의 앞 49컬럼은 같은 봉투다")
	fun bothTablesShareTheEnvelope() {
		assertThat(EVENTS_DDL.take(ENVELOPE_SIZE)).containsExactlyElementsOf(METRIC_POINTS_DDL.take(ENVELOPE_SIZE))
	}

	@Test
	@DisplayName("EventObservation 의 고유 필드 = telemetry_events 의 50–107 컬럼, 순서까지")
	fun eventObservationMatchesTheEventsTable() {
		assertThat(EVENTS_DDL).hasSize(107)
		assertThat(columnsOf(EventObservation::class) - "envelope")
			.containsExactlyElementsOf(EVENTS_DDL.drop(ENVELOPE_SIZE))
	}

	@Test
	@DisplayName("MetricPointObservation 의 고유 필드 = telemetry_metric_points 의 50–85 컬럼, 순서까지")
	fun metricPointObservationMatchesTheMetricPointsTable() {
		assertThat(METRIC_POINTS_DDL).hasSize(85)
		assertThat(columnsOf(MetricPointObservation::class) - "envelope")
			.containsExactlyElementsOf(METRIC_POINTS_DDL.drop(ENVELOPE_SIZE))
	}

	/** 주 생성자 매개변수 순서의 snake_case 이름. */
	private fun columnsOf(type: KClass<*>): List<String> =
		type.primaryConstructor!!.parameters.map { snake(it.name!!) }

	private fun snake(name: String): String = name.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()

	private companion object {
		const val ENVELOPE_SIZE = 49

		/** `V2__telemetry_analysis_tables.sql` 의 컬럼 순서. 봉투를 고치면 두 목록을 같은 커밋에서 고친다. */
		val EVENTS_DDL: List<String> = listOf(
			"tenant_id", "installation_id", "observation_id", "row_version", "normalizer_rev", "analysis_hash",
			"schema_version", "identity_version", "source_identity_kind", "source_identity_namespace", "native_observation_id", "record_status",
			"exclusion_reason", "mapping_status", "quality_flags", "source_time", "source_time_origin", "event_time",
			"observed_time", "received_time", "signal", "product", "surface", "product_version",
			"service_name", "service_version", "service_instance_id", "scope_name", "scope_version", "resource_schema_url",
			"scope_schema_url", "original_name", "mapping_version", "enrichment_version", "usage_role", "usage_scope",
			"workload_kind", "session_id", "session_id_namespace", "model", "member_id", "team_id_as_of",
			"team_ids_as_of", "archive_ref", "archive_selector", "masking_version", "metadata_json", "attrs",
			"enrichment_json", "event_type", "operation", "end_time", "duration_ns", "ttft_ns",
			"ttft_scope", "turn_id", "turn_id_namespace", "request_id", "request_id_namespace", "client_request_id",
			"client_request_id_namespace", "response_id", "response_id_namespace", "call_id", "call_id_namespace", "tool_result_seq",
			"trace_id", "span_id", "parent_span_id", "span_kind", "span_status_code", "severity_number",
			"tokens_input", "tokens_output", "tokens_cache_read", "tokens_cache_create", "tokens_reasoning", "tokens_tool",
			"tokens_total_reported", "tokens_total_derived", "tokens_input_uncached", "input_semantics", "output_semantics", "semantics_profile",
			"cost_reported_usd", "cost_estimated_usd", "reported_cost_basis", "pricing_version", "success", "http_status",
			"error_type", "attempt", "tool_name", "tool_namespace", "tool_origin", "tool_action",
			"mcp_server", "decision", "decision_raw", "decision_source", "decision_scope", "prompt_length",
			"response_length", "command_name", "stop_reason", "reasoning_effort", "agent_id",
		)
		val METRIC_POINTS_DDL: List<String> = listOf(
			"tenant_id", "installation_id", "observation_id", "row_version", "normalizer_rev", "analysis_hash",
			"schema_version", "identity_version", "source_identity_kind", "source_identity_namespace", "native_observation_id", "record_status",
			"exclusion_reason", "mapping_status", "quality_flags", "source_time", "source_time_origin", "event_time",
			"observed_time", "received_time", "signal", "product", "surface", "product_version",
			"service_name", "service_version", "service_instance_id", "scope_name", "scope_version", "resource_schema_url",
			"scope_schema_url", "original_name", "mapping_version", "enrichment_version", "usage_role", "usage_scope",
			"workload_kind", "session_id", "session_id_namespace", "model", "member_id", "team_id_as_of",
			"team_ids_as_of", "archive_ref", "archive_selector", "masking_version", "metadata_json", "attrs",
			"enrichment_json", "series_id", "series_identity_status", "structural_status", "metric_name", "metric_type",
			"metric_family", "metric_profile", "description", "raw_unit", "canonical_unit", "token_component",
			"temporality", "temporality_code", "is_monotonic", "start_time", "value_int", "value_double",
			"hist_count", "hist_sum", "hist_min", "hist_max", "hist_buckets_present", "explicit_bounds",
			"bucket_counts", "exp_scale", "exp_zero_count", "exp_zero_threshold", "exp_positive_offset", "exp_positive_buckets",
			"exp_negative_offset", "exp_negative_buckets", "summary_count", "summary_sum", "quantiles", "quantile_values",
			"point_flags",
		)
	}
}
