package com.team376.pulsemetry.telemetry.adapter.source.claudecode

import com.team376.pulsemetry.telemetry.adapter.IngestContext
import com.team376.pulsemetry.telemetry.adapter.OtlpRecord
import com.team376.pulsemetry.telemetry.adapter.model.Normalized
import com.team376.pulsemetry.telemetry.adapter.model.SignalType
import com.team376.pulsemetry.telemetry.adapter.source.MetricEvents
import com.team376.pulsemetry.telemetry.adapter.source.TelemetrySource

/**
 * claude_code 소스. 이벤트 16종 — 로그 9 · 스팬 6 · 메트릭 통과.
 *
 * 매칭은 **리더가 준 이름**으로 한다. 신호마다 그 이름이 나오는 자리는 다르지만
 * (로그 본문 / 스팬 이름 / 메트릭 이름) 어느 쪽이든 `claude_code.` 로 시작하면 우리 것이다.
 * codex 와 달리 신호에 따라 보는 자리를 바꾸지 않는다.
 */
internal object ClaudeCodeSource : TelemetrySource {

	override fun match(record: OtlpRecord): String? =
		record.name.takeIf { it.startsWith(ClaudeCodeCommon.PREFIX) }

	override fun toEvent(
		record: OtlpRecord,
		eventName: String,
		context: IngestContext,
	): Normalized? = when (record.signal) {
		SignalType.LOG -> ClaudeCodeLogs.toEvent(record, eventName, context)
		SignalType.SPAN -> ClaudeCodeTraces.toEvent(record, eventName, context)
		SignalType.METRIC -> MetricEvents.build(
			record = record,
			context = context,
			identity = ClaudeCodeCommon.identity(
				record.resourceAttributes, record.attributes, context.tenantId,
			),
			client = ClaudeCodeCommon.client(record.resourceAttributes, record.attributes),
			adapterVersion = ClaudeCodeCommon.ADAPTER_VERSION,
		)
	}
}
