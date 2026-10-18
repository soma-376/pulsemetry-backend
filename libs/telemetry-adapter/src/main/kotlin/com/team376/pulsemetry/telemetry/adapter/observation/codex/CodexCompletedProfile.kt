package com.team376.pulsemetry.telemetry.adapter.observation.codex

import com.team376.pulsemetry.telemetry.adapter.observation.EventObservation
import com.team376.pulsemetry.telemetry.adapter.observation.MetadataAllowlist
import com.team376.pulsemetry.telemetry.adapter.observation.Product
import com.team376.pulsemetry.telemetry.adapter.observation.Surface
import com.team376.pulsemetry.telemetry.adapter.observation.profile.FieldReader
import com.team376.pulsemetry.telemetry.adapter.observation.profile.LogProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.LogRecordView
import com.team376.pulsemetry.telemetry.adapter.observation.profile.MappedEvent
import com.team376.pulsemetry.telemetry.adapter.observation.profile.MetricProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.ProductProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.SpanProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.VersionSet

/**
 * 실캡처·원천 구조 감사에서 확인한 완료 로그만의 프로파일(ADR 0020 §4·부록 C).
 * 근거는 `otlp-v2/codex/completed/README.md`와 `PROFILE-EVIDENCE.md` §11이다.
 * 전체 Codex 프로파일의 검증 범위를 넓히지 않는다.
 *
 * 정확한 버전·표면·emitter·완료 분기를 모두 확인한다. 다른 로그·스팬·메트릭은 generic 이며,
 * 확인한 완료 분기의 토큰 포함관계는 [CodexTokenSemantics]로 연결한다(ADR 0061). 가격은 별도 단계가 적용한다.
 */
public object CodexCompletedProfile : ProductProfile {

	override val product: Product = Product.CODEX
	override val versions: VersionSet = VersionSet.exactly("0.158.0-alpha.2.1", "0.159.2", "0.162.0-alpha.17.2")
	override val surfaces: Set<Surface> = setOf(Surface.APP_SERVER)
	override val mappingVersion: String = "codex-completed-v2"
	override val logs: LogProfile = CompletedLogs
	override val spans: SpanProfile? = null
	override val metrics: MetricProfile? = null

	private object CompletedLogs : LogProfile {
		override val allowlist: MetadataAllowlist = MetadataAllowlist(
			version = "codex-completed-logs-v1",
			resource = setOf("service.name", "service.version", "telemetry.sdk.name", "telemetry.sdk.language", "telemetry.sdk.version"),
			record = setOf(
				"event.name", "event.kind", "event.timestamp", "conversation.id", "model",
				"input_token_count", "output_token_count", "cached_token_count", "cache_write_token_count",
				"reasoning_token_count", "tool_token_count", "ttft_ms", "service_tier", "model_reasoning_effort",
			),
		)

		override fun semanticName(view: LogRecordView): String? = FieldReader(view.attributes).nonEmptyText("event.name")

		override fun map(name: String, view: LogRecordView, base: EventObservation): MappedEvent? {
			if (name != "codex.sse_event" || !CodexTokenSemantics.isCompletedEmitter(base.envelope.serviceVersion, view.eventName)) return null
			if (FieldReader(view.attributes).nonEmptyText("event.kind") != "response.completed") return null
			// 기존 검증된 SSE 분기의 wire 검사·토큰/오류 충돌 검사를 그대로 적용한다.
			return CodexLogs.map(name, view, base)
		}
	}
}
