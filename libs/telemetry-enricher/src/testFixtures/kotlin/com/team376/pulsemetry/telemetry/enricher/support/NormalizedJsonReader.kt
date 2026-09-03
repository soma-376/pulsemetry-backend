package com.team376.pulsemetry.telemetry.enricher.support

import com.team376.pulsemetry.telemetry.adapter.model.Client
import com.team376.pulsemetry.telemetry.adapter.model.Decision
import com.team376.pulsemetry.telemetry.adapter.model.DecisionScope
import com.team376.pulsemetry.telemetry.adapter.model.DecisionSource
import com.team376.pulsemetry.telemetry.adapter.model.Envelope
import com.team376.pulsemetry.telemetry.adapter.model.Identity
import com.team376.pulsemetry.telemetry.adapter.model.Ingest
import com.team376.pulsemetry.telemetry.adapter.model.Lifecycle
import com.team376.pulsemetry.telemetry.adapter.model.LlmCall
import com.team376.pulsemetry.telemetry.adapter.model.LlmResponse
import com.team376.pulsemetry.telemetry.adapter.model.LogKind
import com.team376.pulsemetry.telemetry.adapter.model.LogPayload
import com.team376.pulsemetry.telemetry.adapter.model.MetricPoint
import com.team376.pulsemetry.telemetry.adapter.model.Normalized
import com.team376.pulsemetry.telemetry.adapter.model.NormalizedLog
import com.team376.pulsemetry.telemetry.adapter.model.NormalizedMetric
import com.team376.pulsemetry.telemetry.adapter.model.NormalizedSpan
import com.team376.pulsemetry.telemetry.adapter.model.Payload
import com.team376.pulsemetry.telemetry.adapter.model.Prompt
import com.team376.pulsemetry.telemetry.adapter.model.SignalType
import com.team376.pulsemetry.telemetry.adapter.model.SpanKind
import com.team376.pulsemetry.telemetry.adapter.model.SpanPayload
import com.team376.pulsemetry.telemetry.adapter.model.Surface
import com.team376.pulsemetry.telemetry.adapter.model.Tokens
import com.team376.pulsemetry.telemetry.adapter.model.ToolAction
import com.team376.pulsemetry.telemetry.adapter.model.ToolCall
import com.team376.pulsemetry.telemetry.adapter.model.ToolDecision
import com.team376.pulsemetry.telemetry.adapter.model.ToolKind
import com.team376.pulsemetry.telemetry.adapter.model.ValueSource
import com.team376.pulsemetry.telemetry.adapter.model.WireValued

/**
 * golden fixture 의 `event` 트리를 [Normalized] 로 되돌린다. **테스트 전용이다.**
 *
 * 어댑터의 `NormalizedJson` 은 쓰기 전용이라 짝이 없다. 보강·적재 단계는 OTLP 를 파싱하지
 * 않으므로, 실측 이벤트를 입력으로 쓰려면 기대출력 쪽에서 되읽는 이 길밖에 없다.
 *
 * ## 이 리더를 믿어도 되는 근거
 *
 * `NormalizedJsonReaderRoundTripTest` 가 fixture 의 **모든 줄**에 대해
 * `NormalizedJson.toJson(read(line))` 이 원래 줄과 값 동등한지 확인한다. 그 테스트가 green 인
 * 동안에는 이 리더가 만든 객체가 golden 이 말하는 그 이벤트다.
 *
 * ## payload 판별
 *
 * 직렬화에 판별 태그가 없어서 **키 하나로 가른다.** `NormalizedJson` 이 null 필드까지 전부
 * 싣기 때문에 키의 존재가 곧 타입이다 — `tokens` 는 `LlmCall` 에만, `tool_kind` 는 `ToolCall`
 * 에만 있는 식이다. `type` 으로 가르지 않는 이유는 `other` 가 어느 payload 든 달 수 있어서다.
 */
public object NormalizedJsonReader {

	/** 한 줄의 `event` 값을 이벤트로 되돌린다. */
	public fun read(event: Map<String, Any?>): Normalized {
		val envelope = envelope(event.map("envelope"))
		return when {
			event.containsKey("point") -> NormalizedMetric(envelope, point(event.map("point")))

			event.containsKey("span_id") -> NormalizedSpan(
				envelope = envelope,
				type = wire(SpanKind.entries, event.string("type")),
				payload = event.mapOrNull("payload")?.let { payload(it) as SpanPayload },
				traceId = event.stringOrNull("trace_id"),
				spanId = event.stringOrNull("span_id"),
				parentId = event.stringOrNull("parent_id"),
				callId = event.stringOrNull("call_id"),
			)

			else -> NormalizedLog(
				envelope = envelope,
				type = wire(LogKind.entries, event.string("type")),
				payload = event.mapOrNull("payload")?.let { payload(it) as LogPayload },
				turnId = event.stringOrNull("turn_id"),
				callId = event.stringOrNull("call_id"),
				sequence = event.intOrNull("sequence"),
			)
		}
	}

	private fun envelope(value: Map<String, Any?>): Envelope {
		val identity = value.map("identity")
		val client = value.map("client")
		val ingest = value.map("_ingest")
		return Envelope(
			identity = Identity(
				tenantId = identity.stringOrNull("tenant_id"),
				memberId = identity.stringOrNull("member_id"),
				installationId = identity.stringOrNull("installation_id"),
				vendorEmail = identity.stringOrNull("vendor_email"),
				vendorAccountId = identity.stringOrNull("vendor_account_id"),
			),
			client = Client(
				product = client.string("product"),
				surface = wire(Surface.entries, client.string("surface")),
				version = client.stringOrNull("version"),
			),
			timestamp = value.double("timestamp"),
			sessionId = value.string("session_id"),
			ingest = Ingest(
				adapterVersion = ingest.int("adapter_version"),
				signal = wire(SignalType.entries, ingest.string("signal")),
				sourceRecordId = ingest.stringOrNull("source_record_id"),
				callIdInferred = ingest["call_id_inferred"] as Boolean,
			),
			schemaVersion = value.int("schema_version"),
			recordId = value.string("record_id"),
		)
	}

	private fun payload(value: Map<String, Any?>): Payload = when {
		value.containsKey("tokens") -> LlmCall(
			model = value.stringOrNull("model"),
			tokens = tokens(value.map("tokens")),
			costUsd = value.doubleOrNull("cost_usd"),
			costSource = wire(ValueSource.entries, value.string("cost_source")),
			source = value.stringOrNull("source"),
			reasoningEffort = value.stringOrNull("reasoning_effort"),
			durationMs = value.intOrNull("duration_ms"),
			ttftMs = value.intOrNull("ttft_ms"),
			stopReason = value.stringOrNull("stop_reason"),
			attempt = value.intOrNull("attempt"),
			requestId = value.stringOrNull("request_id"),
			errorType = value.stringOrNull("error_type"),
			statusCode = value.intOrNull("status_code"),
		)

		value.containsKey("response_length") -> LlmResponse(
			model = value.stringOrNull("model"),
			responseLength = value.intOrNull("response_length"),
			source = value.stringOrNull("source"),
			requestId = value.stringOrNull("request_id"),
			stopReason = value.stringOrNull("stop_reason"),
			refusalCategory = value.stringOrNull("refusal_category"),
		)

		value.containsKey("command_name") -> Prompt(
			length = value.intOrNull("length"),
			commandName = value.stringOrNull("command_name"),
		)

		value.containsKey("tool_kind") -> ToolCall(
			toolName = value.stringOrNull("tool_name"),
			toolKind = wire(ToolKind.entries, value.string("tool_kind")),
			action = wire(ToolAction.entries, value.string("action")),
			files = value.strings("files"),
			command = value.stringOrNull("command"),
			success = value["success"] as Boolean?,
			errorType = value.stringOrNull("error_type"),
			durationMs = value.intOrNull("duration_ms"),
			mcpServer = value.stringOrNull("mcp_server"),
			agentId = value.stringOrNull("agent_id"),
			parentAgentId = value.stringOrNull("parent_agent_id"),
		)

		value.containsKey("decision") -> ToolDecision(
			decision = wire(Decision.entries, value.string("decision")),
			decidedBy = wire(DecisionSource.entries, value.string("decided_by")),
			scope = wire(DecisionScope.entries, value.string("scope")),
			blockedOnUserMs = value.intOrNull("blocked_on_user_ms"),
			toolName = value.stringOrNull("tool_name"),
		)

		value.containsKey("kind") -> Lifecycle(
			kind = value.string("kind"),
			startType = value.stringOrNull("start_type"),
			activeTimeSec = value.intOrNull("active_time_sec"),
			turnCount = value.intOrNull("turn_count"),
			tokensBefore = value.intOrNull("tokens_before"),
			tokensAfter = value.intOrNull("tokens_after"),
			attrs = value.attrs("attrs"),
		)

		else -> error("판별할 수 없는 payload 다: ${value.keys}")
	}

	private fun tokens(value: Map<String, Any?>): Tokens = Tokens(
		input = value.intOrNull("input"),
		output = value.intOrNull("output"),
		cacheRead = value.intOrNull("cache_read"),
		cacheCreate = value.intOrNull("cache_create"),
		reasoning = value.intOrNull("reasoning"),
		tool = value.intOrNull("tool"),
		totalReported = value.intOrNull("total_reported"),
	)

	private fun point(value: Map<String, Any?>): MetricPoint = MetricPoint(
		name = value.string("name"),
		// 값 타입이 원본 그대로다 — 정수와 실수의 구분이 golden 대조의 판정 대상이라 눕히지 않는다.
		value = value["value"] as Number?,
		unit = value.stringOrNull("unit"),
		description = value.stringOrNull("description"),
		metricType = value.stringOrNull("metric_type"),
		aggregationTemporality = value.intOrNull("aggregation_temporality"),
		isMonotonic = value["is_monotonic"] as Boolean?,
		startTime = value.doubleOrNull("start_time"),
		count = value.intOrNull("count"),
		sum = value.doubleOrNull("sum"),
		min = value.doubleOrNull("min"),
		max = value.doubleOrNull("max"),
		bucketCounts = (value["bucket_counts"] as List<*>).map { (it as Number).toInt() },
		explicitBounds = (value["explicit_bounds"] as List<*>).map { (it as Number).toDouble() },
		attrs = value.attrs("attrs"),
	)

	private fun <T : WireValued> wire(entries: List<T>, wire: String): T =
		entries.firstOrNull { it.wire == wire } ?: error("모르는 enum 값이다: $wire")

	@Suppress("UNCHECKED_CAST")
	private fun Map<String, Any?>.map(key: String): Map<String, Any?> = this[key] as Map<String, Any?>

	@Suppress("UNCHECKED_CAST")
	private fun Map<String, Any?>.mapOrNull(key: String): Map<String, Any?>? = this[key] as Map<String, Any?>?

	private fun Map<String, Any?>.string(key: String): String = this[key] as String

	private fun Map<String, Any?>.stringOrNull(key: String): String? = this[key] as String?

	private fun Map<String, Any?>.int(key: String): Int = (this[key] as Number).toInt()

	private fun Map<String, Any?>.intOrNull(key: String): Int? = (this[key] as Number?)?.toInt()

	private fun Map<String, Any?>.double(key: String): Double = (this[key] as Number).toDouble()

	private fun Map<String, Any?>.doubleOrNull(key: String): Double? = (this[key] as Number?)?.toDouble()

	private fun Map<String, Any?>.strings(key: String): List<String> =
		(this[key] as List<*>).map { it as String }

	@Suppress("UNCHECKED_CAST")
	private fun Map<String, Any?>.attrs(key: String): Map<String, String> =
		(this[key] as Map<String, String>)
}
