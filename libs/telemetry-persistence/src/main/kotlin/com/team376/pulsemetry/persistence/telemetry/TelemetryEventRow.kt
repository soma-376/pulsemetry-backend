package com.team376.pulsemetry.persistence.telemetry

import com.team376.pulsemetry.telemetry.adapter.observation.RowVersioning
import com.team376.pulsemetry.telemetry.enricher.observation.EnrichedEvent

/**
 * `telemetry_events` 한 행(107컬럼)의 JSONEachRow 표기(ADR 0020 §1·§4). 봉투 49 뒤에 이벤트 고유 58컬럼을 DDL 순서로
 * 쓴다. 값의 표기 규칙은 [AnalysisRowWriter] 가 담는다.
 */
internal object TelemetryEventRow {

	fun toJson(event: EnrichedEvent, versioning: RowVersioning): String {
		val o = event.observation
		val w = AnalysisRowWriter(AnalysisColumns.EVENTS)
		AnalysisEnvelopeRow.write(w, o.envelope, event.org, versioning)
		w.lowCardinality("event_type", o.eventType)
		w.lowCardinality("operation", o.operation)
		w.time("end_time", o.endTime)
		w.int64("duration_ns", o.durationNs)
		w.int64("ttft_ns", o.ttftNs)
		w.lowCardinality("ttft_scope", o.ttftScope)
		w.string("turn_id", o.turnId)
		w.string("turn_id_namespace", o.turnIdNamespace)
		w.string("request_id", o.requestId)
		w.string("request_id_namespace", o.requestIdNamespace)
		w.string("client_request_id", o.clientRequestId)
		w.string("client_request_id_namespace", o.clientRequestIdNamespace)
		w.string("response_id", o.responseId)
		w.string("response_id_namespace", o.responseIdNamespace)
		w.string("call_id", o.callId)
		w.string("call_id_namespace", o.callIdNamespace)
		w.int64("tool_result_seq", o.toolResultSeq)
		w.string("trace_id", o.traceId)
		w.string("span_id", o.spanId)
		w.string("parent_span_id", o.parentSpanId)
		w.lowCardinality("span_kind", o.spanKind)
		w.lowCardinality("span_status_code", o.spanStatusCode)
		w.uint8("severity_number", o.severityNumber)
		w.int64("tokens_input", o.tokensInput)
		w.int64("tokens_output", o.tokensOutput)
		w.int64("tokens_cache_read", o.tokensCacheRead)
		w.int64("tokens_cache_create", o.tokensCacheCreate)
		w.int64("tokens_reasoning", o.tokensReasoning)
		w.int64("tokens_tool", o.tokensTool)
		w.int64("tokens_total_reported", o.tokensTotalReported)
		w.int64("tokens_total_derived", o.tokensTotalDerived)
		w.int64("tokens_input_uncached", o.tokensInputUncached)
		w.lowCardinality("input_semantics", o.inputSemantics)
		w.lowCardinality("output_semantics", o.outputSemantics)
		w.string("semantics_profile", o.semanticsProfile)
		w.money("cost_reported_usd", o.costReportedUsd)
		w.money("cost_estimated_usd", o.costEstimatedUsd)
		w.lowCardinality("reported_cost_basis", o.reportedCostBasis)
		w.string("pricing_version", o.pricingVersion)
		w.bool("success", o.success)
		w.uint16("http_status", o.httpStatus)
		w.lowCardinality("error_type", o.errorType)
		w.int64("attempt", o.attempt)
		w.string("tool_name", o.toolName)
		w.string("tool_namespace", o.toolNamespace)
		w.lowCardinality("tool_origin", o.toolOrigin)
		w.lowCardinality("tool_action", o.toolAction)
		w.string("mcp_server", o.mcpServer)
		w.lowCardinality("decision", o.decision)
		w.string("decision_raw", o.decisionRaw)
		w.lowCardinality("decision_source", o.decisionSource)
		w.lowCardinality("decision_scope", o.decisionScope)
		w.int64("prompt_length", o.promptLength)
		w.int64("response_length", o.responseLength)
		w.string("command_name", o.commandName)
		w.lowCardinality("stop_reason", o.stopReason)
		w.lowCardinality("reasoning_effort", o.reasoningEffort)
		w.string("agent_id", o.agentId)
		return w.build()
	}
}
