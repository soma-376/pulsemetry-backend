package com.team376.pulsemetry.telemetry.adapter

import com.team376.pulsemetry.telemetry.adapter.model.Client
import com.team376.pulsemetry.telemetry.adapter.model.Envelope
import com.team376.pulsemetry.telemetry.adapter.model.Identity
import com.team376.pulsemetry.telemetry.adapter.model.Ingest
import com.team376.pulsemetry.telemetry.adapter.model.Lifecycle
import com.team376.pulsemetry.telemetry.adapter.model.LlmCall
import com.team376.pulsemetry.telemetry.adapter.model.LlmResponse
import com.team376.pulsemetry.telemetry.adapter.model.MetricPoint
import com.team376.pulsemetry.telemetry.adapter.model.Normalized
import com.team376.pulsemetry.telemetry.adapter.model.NormalizedLog
import com.team376.pulsemetry.telemetry.adapter.model.NormalizedMetric
import com.team376.pulsemetry.telemetry.adapter.model.NormalizedSpan
import com.team376.pulsemetry.telemetry.adapter.model.Prompt
import com.team376.pulsemetry.telemetry.adapter.model.Tokens
import com.team376.pulsemetry.telemetry.adapter.model.ToolCall
import com.team376.pulsemetry.telemetry.adapter.model.ToolDecision

/**
 * 정규화 이벤트의 JSON 표현 — ClickHouse `raw_json` 에 실제로 들어가는 그 형태.
 *
 * ## 계약
 *
 * - 봉투가 중첩되고, enum 은 값 문자열이다
 * - 판별 태그가 없다 — `point` 가 있으면 메트릭, `type`+`payload` 면 로그나 스팬이다
 * - 키 이름은 **snake_case** 다. Kotlin 프로퍼티 이름(camelCase)이 아니다
 * - `_ingest` 는 밑줄이 붙는다
 * - [Tokens.billable]·[Tokens.reconciles] 는 **실리지 않는다** — 계산값이다
 *
 * golden fixture 의 `event` 필드가 정확히 이 값이다. 대조는 키 순서가 아니라 **값 동등성**으로
 * 한다. 그래도 필드 순서는 이식 원본의 선언 순서를 따라 두어 diff 가 읽히게 한다.
 */
public object NormalizedJson {

	/** 이벤트를 JSON 트리로 편다. 값은 String·Number·Boolean·List·Map·null 뿐이다. */
	public fun toTree(event: Normalized): Map<String, Any?> = when (event) {
		is NormalizedLog -> linkedMapOf(
			"envelope" to envelope(event.envelope),
			"type" to event.type.wire,
			"payload" to payload(event.payload),
			"turn_id" to event.turnId,
			"call_id" to event.callId,
			"sequence" to event.sequence,
		)

		is NormalizedSpan -> linkedMapOf(
			"envelope" to envelope(event.envelope),
			"type" to event.type.wire,
			"payload" to payload(event.payload),
			"trace_id" to event.traceId,
			"span_id" to event.spanId,
			"parent_id" to event.parentId,
			"call_id" to event.callId,
		)

		is NormalizedMetric -> linkedMapOf(
			"envelope" to envelope(event.envelope),
			"point" to point(event.point),
		)
	}

	/** 한 줄 JSON. 공백 없는 표기다. */
	public fun toJson(event: Normalized): String = CompactJson.encode(toTree(event))

	private fun envelope(value: Envelope): Map<String, Any?> = linkedMapOf(
		"identity" to identity(value.identity),
		"client" to client(value.client),
		"timestamp" to value.timestamp,
		"session_id" to value.sessionId,
		"schema_version" to value.schemaVersion,
		"record_id" to value.recordId,
		"_ingest" to ingest(value.ingest),
	)

	private fun identity(value: Identity): Map<String, Any?> = linkedMapOf(
		"tenant_id" to value.tenantId,
		"member_id" to value.memberId,
		"installation_id" to value.installationId,
		"vendor_email" to value.vendorEmail,
		"vendor_account_id" to value.vendorAccountId,
	)

	private fun client(value: Client): Map<String, Any?> = linkedMapOf(
		"product" to value.product,
		"surface" to value.surface.wire,
		"version" to value.version,
	)

	private fun ingest(value: Ingest): Map<String, Any?> = linkedMapOf(
		"adapter_version" to value.adapterVersion,
		"signal" to value.signal.wire,
		"source_record_id" to value.sourceRecordId,
		"call_id_inferred" to value.callIdInferred,
	)

	private fun payload(value: Any?): Map<String, Any?>? = when (value) {
		null -> null

		is LlmCall -> linkedMapOf(
			"model" to value.model,
			"tokens" to tokens(value.tokens),
			"cost_usd" to value.costUsd,
			"cost_source" to value.costSource.wire,
			"source" to value.source,
			"reasoning_effort" to value.reasoningEffort,
			"duration_ms" to value.durationMs,
			"ttft_ms" to value.ttftMs,
			"stop_reason" to value.stopReason,
			"attempt" to value.attempt,
			"request_id" to value.requestId,
			"error_type" to value.errorType,
			"status_code" to value.statusCode,
		)

		is LlmResponse -> linkedMapOf(
			"model" to value.model,
			"response_length" to value.responseLength,
			"source" to value.source,
			"request_id" to value.requestId,
			"stop_reason" to value.stopReason,
			"refusal_category" to value.refusalCategory,
		)

		is Prompt -> linkedMapOf(
			"length" to value.length,
			"command_name" to value.commandName,
		)

		is ToolCall -> linkedMapOf(
			"tool_name" to value.toolName,
			"tool_kind" to value.toolKind.wire,
			"action" to value.action.wire,
			"files" to value.files,
			"command" to value.command,
			"success" to value.success,
			"error_type" to value.errorType,
			"duration_ms" to value.durationMs,
			"mcp_server" to value.mcpServer,
			"agent_id" to value.agentId,
			"parent_agent_id" to value.parentAgentId,
		)

		is ToolDecision -> linkedMapOf(
			"decision" to value.decision.wire,
			"decided_by" to value.decidedBy.wire,
			"scope" to value.scope.wire,
			"blocked_on_user_ms" to value.blockedOnUserMs,
			"tool_name" to value.toolName,
		)

		is Lifecycle -> linkedMapOf(
			"kind" to value.kind,
			"start_type" to value.startType,
			"active_time_sec" to value.activeTimeSec,
			"turn_count" to value.turnCount,
			"tokens_before" to value.tokensBefore,
			"tokens_after" to value.tokensAfter,
			"attrs" to value.attrs,
		)

		else -> throw IllegalArgumentException("직렬화 규칙이 없는 payload 다: ${value::class}")
	}

	/** [Tokens.billable]·[Tokens.reconciles] 는 계산값이라 여기 없다. */
	private fun tokens(value: Tokens): Map<String, Any?> = linkedMapOf(
		"input" to value.input,
		"output" to value.output,
		"cache_read" to value.cacheRead,
		"cache_create" to value.cacheCreate,
		"reasoning" to value.reasoning,
		"tool" to value.tool,
		"total_reported" to value.totalReported,
	)

	private fun point(value: MetricPoint): Map<String, Any?> = linkedMapOf(
		"name" to value.name,
		"value" to value.value,
		"unit" to value.unit,
		"description" to value.description,
		"metric_type" to value.metricType,
		"aggregation_temporality" to value.aggregationTemporality,
		"is_monotonic" to value.isMonotonic,
		"start_time" to value.startTime,
		"count" to value.count,
		"sum" to value.sum,
		"min" to value.min,
		"max" to value.max,
		"bucket_counts" to value.bucketCounts,
		"explicit_bounds" to value.explicitBounds,
		"attrs" to value.attrs,
	)
}
