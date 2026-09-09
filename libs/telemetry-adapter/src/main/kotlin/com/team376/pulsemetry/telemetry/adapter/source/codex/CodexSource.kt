package com.team376.pulsemetry.telemetry.adapter.source.codex

import com.team376.pulsemetry.telemetry.adapter.IngestContext
import com.team376.pulsemetry.telemetry.adapter.OtlpRecord
import com.team376.pulsemetry.telemetry.adapter.model.Normalized
import com.team376.pulsemetry.telemetry.adapter.model.SignalType
import com.team376.pulsemetry.telemetry.adapter.source.MetricEvents
import com.team376.pulsemetry.telemetry.adapter.source.TelemetrySource

/**
 * codex 소스. 이벤트 11종 — 로그 6 · 스팬 4 · 메트릭 통과.
 *
 * ## 매칭이 claude_code 와 다르다
 *
 * **로그일 때는 본문을 보지 않고 `event.name` 속성을 본다.** codex 는 이벤트 이름을 구조화
 * 속성으로 싣기 때문이다. 스팬·메트릭은 리더가 준 이름(스팬 이름·메트릭 이름)을 쓴다.
 *
 * 그 결과 본문이 `codex.user_prompt` 여도 `event.name` 속성이 없으면 이 소스는 물지 않는다 —
 * golden fixture 의 codex/logs_synthetic 이 그 경계를 고정하고 있다.
 */
internal object CodexSource : TelemetrySource {

	override fun match(record: OtlpRecord): String? {
		val candidate = if (record.signal == SignalType.LOG) {
			record.attributes["event.name"] as? String
		} else {
			record.name
		}
		return candidate?.takeIf { it.startsWith(CodexCommon.PREFIX) }
	}

	override fun toEvent(
		record: OtlpRecord,
		eventName: String,
		context: IngestContext,
	): Normalized? = when (record.signal) {
		SignalType.LOG -> CodexLogs.toEvent(record, eventName, context)
		SignalType.SPAN -> CodexTraces.toEvent(record, eventName, context)
		SignalType.METRIC -> MetricEvents.build(
			record = record,
			context = context,
			identity = CodexCommon.identity(
				record.resourceAttributes, record.attributes, context.tenantId,
			),
			client = CodexCommon.client(record.resourceAttributes, record.attributes),
			adapterVersion = CodexCommon.ADAPTER_VERSION,
		)
	}
}
