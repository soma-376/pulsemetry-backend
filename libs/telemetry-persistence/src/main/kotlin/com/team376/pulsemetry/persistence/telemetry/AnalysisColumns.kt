package com.team376.pulsemetry.persistence.telemetry

/**
 * 분석 테이블 컬럼 하나 — 이름과 ClickHouse 타입(`system.columns.type` 표기 그대로).
 *
 * [type] 이 `Nullable(…)` 이면 null 을 쓸 수 있다. 행 인코더는 컬럼마다 이 선언과 이름·순서·기반 타입·null 가능성을
 * 대조한다([AnalysisRowWriter]).
 */
internal class ColumnSpec(val name: String, val type: String) {

	val nullable: Boolean get() = type.startsWith(NULLABLE)

	/** `Nullable(…)` 을 벗긴 타입. */
	val baseType: String get() = if (nullable) type.substring(NULLABLE.length, type.length - 1) else type

	override fun toString(): String = "$name $type"

	private companion object {
		const val NULLABLE = "Nullable("
	}
}

/**
 * 두 분석 테이블(`V2__telemetry_analysis_tables.sql`)의 컬럼 선언 — 인코더가 믿는 스키마다(ADR 0020 §1·부록 A).
 *
 * 진실원은 DDL 이다. 이 목록이 실제 테이블의 `system.columns` 와 이름·타입·순서까지 같은지는 ClickHouse 컨테이너 테스트가
 * 본다. 봉투(앞 49컬럼)는 두 테이블이 공유한다 — 봉투를 고칠 때는 DDL 과 이 목록을 같은 커밋에서 고친다.
 */
internal object AnalysisColumns {

	private const val LOW_CARDINALITY = "LowCardinality(String)"
	private const val STRING = "String"
	private const val NULLABLE_STRING = "Nullable(String)"
	private const val HEX64 = "FixedString(64)"
	private const val NULLABLE_HEX64 = "Nullable(FixedString(64))"
	private const val TIME = "DateTime64(9, 'UTC')"
	private const val NULLABLE_TIME = "Nullable(DateTime64(9, 'UTC'))"
	private const val NULLABLE_INT32 = "Nullable(Int32)"
	private const val NULLABLE_INT64 = "Nullable(Int64)"
	private const val NULLABLE_UINT8 = "Nullable(UInt8)"
	private const val UINT16 = "UInt16"
	private const val NULLABLE_UINT16 = "Nullable(UInt16)"
	private const val UINT32 = "UInt32"
	private const val UINT64 = "UInt64"
	private const val NULLABLE_UINT64 = "Nullable(UInt64)"
	private const val BOOL = "Bool"
	private const val NULLABLE_BOOL = "Nullable(Bool)"
	private const val NULLABLE_MONEY = "Nullable(Decimal(38, 12))"
	private const val NULLABLE_FLOAT64 = "Nullable(Float64)"
	private const val FLOAT64_ARRAY = "Array(Float64)"
	private const val UINT64_ARRAY = "Array(UInt64)"
	private const val STRING_ARRAY = "Array(String)"
	private const val LOW_CARDINALITY_ARRAY = "Array(LowCardinality(String))"
	private const val STRING_MAP = "Map(LowCardinality(String), String)"

	private val ENVELOPE: List<ColumnSpec> = listOf(
		ColumnSpec("tenant_id", LOW_CARDINALITY),
		ColumnSpec("installation_id", STRING),
		ColumnSpec("observation_id", HEX64),
		ColumnSpec("row_version", UINT64),
		ColumnSpec("normalizer_rev", UINT32),
		ColumnSpec("analysis_hash", HEX64),
		ColumnSpec("schema_version", UINT16),
		ColumnSpec("identity_version", STRING),
		ColumnSpec("source_identity_kind", LOW_CARDINALITY),
		ColumnSpec("source_identity_namespace", NULLABLE_STRING),
		ColumnSpec("native_observation_id", NULLABLE_STRING),
		ColumnSpec("record_status", LOW_CARDINALITY),
		ColumnSpec("exclusion_reason", NULLABLE_STRING),
		ColumnSpec("mapping_status", LOW_CARDINALITY),
		ColumnSpec("quality_flags", LOW_CARDINALITY_ARRAY),
		ColumnSpec("source_time", TIME),
		ColumnSpec("source_time_origin", LOW_CARDINALITY),
		ColumnSpec("event_time", NULLABLE_TIME),
		ColumnSpec("observed_time", NULLABLE_TIME),
		ColumnSpec("received_time", TIME),
		ColumnSpec("signal", LOW_CARDINALITY),
		ColumnSpec("product", LOW_CARDINALITY),
		ColumnSpec("surface", LOW_CARDINALITY),
		ColumnSpec("product_version", NULLABLE_STRING),
		ColumnSpec("service_name", NULLABLE_STRING),
		ColumnSpec("service_version", NULLABLE_STRING),
		ColumnSpec("service_instance_id", NULLABLE_STRING),
		ColumnSpec("scope_name", NULLABLE_STRING),
		ColumnSpec("scope_version", NULLABLE_STRING),
		ColumnSpec("resource_schema_url", NULLABLE_STRING),
		ColumnSpec("scope_schema_url", NULLABLE_STRING),
		ColumnSpec("original_name", NULLABLE_STRING),
		ColumnSpec("mapping_version", STRING),
		ColumnSpec("enrichment_version", STRING),
		ColumnSpec("usage_role", LOW_CARDINALITY),
		ColumnSpec("usage_scope", LOW_CARDINALITY),
		ColumnSpec("workload_kind", LOW_CARDINALITY),
		ColumnSpec("session_id", NULLABLE_STRING),
		ColumnSpec("session_id_namespace", NULLABLE_STRING),
		ColumnSpec("model", NULLABLE_STRING),
		ColumnSpec("member_id", NULLABLE_STRING),
		ColumnSpec("team_id_as_of", NULLABLE_STRING),
		ColumnSpec("team_ids_as_of", STRING_ARRAY),
		ColumnSpec("archive_ref", NULLABLE_STRING),
		ColumnSpec("archive_selector", NULLABLE_STRING),
		ColumnSpec("masking_version", STRING),
		ColumnSpec("metadata_json", STRING),
		ColumnSpec("attrs", STRING_MAP),
		ColumnSpec("enrichment_json", STRING),
	)

	private val EVENT_BODY: List<ColumnSpec> = listOf(
		ColumnSpec("event_type", LOW_CARDINALITY),
		ColumnSpec("operation", LOW_CARDINALITY),
		ColumnSpec("end_time", NULLABLE_TIME),
		ColumnSpec("duration_ns", NULLABLE_INT64),
		ColumnSpec("ttft_ns", NULLABLE_INT64),
		ColumnSpec("ttft_scope", LOW_CARDINALITY),
		ColumnSpec("turn_id", NULLABLE_STRING),
		ColumnSpec("turn_id_namespace", NULLABLE_STRING),
		ColumnSpec("request_id", NULLABLE_STRING),
		ColumnSpec("request_id_namespace", NULLABLE_STRING),
		ColumnSpec("client_request_id", NULLABLE_STRING),
		ColumnSpec("client_request_id_namespace", NULLABLE_STRING),
		ColumnSpec("response_id", NULLABLE_STRING),
		ColumnSpec("response_id_namespace", NULLABLE_STRING),
		ColumnSpec("call_id", NULLABLE_STRING),
		ColumnSpec("call_id_namespace", NULLABLE_STRING),
		ColumnSpec("tool_result_seq", NULLABLE_INT64),
		ColumnSpec("trace_id", NULLABLE_STRING),
		ColumnSpec("span_id", NULLABLE_STRING),
		ColumnSpec("parent_span_id", NULLABLE_STRING),
		ColumnSpec("span_kind", LOW_CARDINALITY),
		ColumnSpec("span_status_code", LOW_CARDINALITY),
		ColumnSpec("severity_number", NULLABLE_UINT8),
		ColumnSpec("tokens_input", NULLABLE_INT64),
		ColumnSpec("tokens_output", NULLABLE_INT64),
		ColumnSpec("tokens_cache_read", NULLABLE_INT64),
		ColumnSpec("tokens_cache_create", NULLABLE_INT64),
		ColumnSpec("tokens_reasoning", NULLABLE_INT64),
		ColumnSpec("tokens_tool", NULLABLE_INT64),
		ColumnSpec("tokens_total_reported", NULLABLE_INT64),
		ColumnSpec("tokens_total_derived", NULLABLE_INT64),
		ColumnSpec("tokens_input_uncached", NULLABLE_INT64),
		ColumnSpec("input_semantics", LOW_CARDINALITY),
		ColumnSpec("output_semantics", LOW_CARDINALITY),
		ColumnSpec("semantics_profile", NULLABLE_STRING),
		ColumnSpec("cost_reported_usd", NULLABLE_MONEY),
		ColumnSpec("cost_estimated_usd", NULLABLE_MONEY),
		ColumnSpec("reported_cost_basis", LOW_CARDINALITY),
		ColumnSpec("pricing_version", NULLABLE_STRING),
		ColumnSpec("success", NULLABLE_BOOL),
		ColumnSpec("http_status", NULLABLE_UINT16),
		ColumnSpec("error_type", LOW_CARDINALITY),
		ColumnSpec("attempt", NULLABLE_INT64),
		ColumnSpec("tool_name", NULLABLE_STRING),
		ColumnSpec("tool_namespace", NULLABLE_STRING),
		ColumnSpec("tool_origin", LOW_CARDINALITY),
		ColumnSpec("tool_action", LOW_CARDINALITY),
		ColumnSpec("mcp_server", NULLABLE_STRING),
		ColumnSpec("decision", LOW_CARDINALITY),
		ColumnSpec("decision_raw", NULLABLE_STRING),
		ColumnSpec("decision_source", LOW_CARDINALITY),
		ColumnSpec("decision_scope", LOW_CARDINALITY),
		ColumnSpec("prompt_length", NULLABLE_INT64),
		ColumnSpec("response_length", NULLABLE_INT64),
		ColumnSpec("command_name", NULLABLE_STRING),
		ColumnSpec("stop_reason", LOW_CARDINALITY),
		ColumnSpec("reasoning_effort", LOW_CARDINALITY),
		ColumnSpec("agent_id", NULLABLE_STRING),
	)

	private val METRIC_POINT_BODY: List<ColumnSpec> = listOf(
		ColumnSpec("series_id", NULLABLE_HEX64),
		ColumnSpec("series_identity_status", LOW_CARDINALITY),
		ColumnSpec("structural_status", LOW_CARDINALITY),
		ColumnSpec("metric_name", STRING),
		ColumnSpec("metric_type", LOW_CARDINALITY),
		ColumnSpec("metric_family", LOW_CARDINALITY),
		ColumnSpec("metric_profile", NULLABLE_STRING),
		ColumnSpec("description", NULLABLE_STRING),
		ColumnSpec("raw_unit", STRING),
		ColumnSpec("canonical_unit", LOW_CARDINALITY),
		ColumnSpec("token_component", LOW_CARDINALITY),
		ColumnSpec("temporality", LOW_CARDINALITY),
		ColumnSpec("temporality_code", NULLABLE_INT32),
		ColumnSpec("is_monotonic", NULLABLE_BOOL),
		ColumnSpec("start_time", NULLABLE_TIME),
		ColumnSpec("value_int", NULLABLE_INT64),
		ColumnSpec("value_double", NULLABLE_FLOAT64),
		ColumnSpec("hist_count", NULLABLE_UINT64),
		ColumnSpec("hist_sum", NULLABLE_FLOAT64),
		ColumnSpec("hist_min", NULLABLE_FLOAT64),
		ColumnSpec("hist_max", NULLABLE_FLOAT64),
		ColumnSpec("hist_buckets_present", BOOL),
		ColumnSpec("explicit_bounds", FLOAT64_ARRAY),
		ColumnSpec("bucket_counts", UINT64_ARRAY),
		ColumnSpec("exp_scale", NULLABLE_INT32),
		ColumnSpec("exp_zero_count", NULLABLE_UINT64),
		ColumnSpec("exp_zero_threshold", NULLABLE_FLOAT64),
		ColumnSpec("exp_positive_offset", NULLABLE_INT32),
		ColumnSpec("exp_positive_buckets", UINT64_ARRAY),
		ColumnSpec("exp_negative_offset", NULLABLE_INT32),
		ColumnSpec("exp_negative_buckets", UINT64_ARRAY),
		ColumnSpec("summary_count", NULLABLE_UINT64),
		ColumnSpec("summary_sum", NULLABLE_FLOAT64),
		ColumnSpec("quantiles", FLOAT64_ARRAY),
		ColumnSpec("quantile_values", FLOAT64_ARRAY),
		ColumnSpec("point_flags", UINT32),
	)

	/** `telemetry_events` 107컬럼. */
	val EVENTS: List<ColumnSpec> = ENVELOPE + EVENT_BODY

	/** `telemetry_metric_points` 85컬럼. */
	val METRIC_POINTS: List<ColumnSpec> = ENVELOPE + METRIC_POINT_BODY
}
