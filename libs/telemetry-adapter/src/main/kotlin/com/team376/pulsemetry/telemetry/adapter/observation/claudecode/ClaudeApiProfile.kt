package com.team376.pulsemetry.telemetry.adapter.observation.claudecode

import com.team376.pulsemetry.telemetry.adapter.observation.EventObservation
import com.team376.pulsemetry.telemetry.adapter.observation.MetadataAllowlist
import com.team376.pulsemetry.telemetry.adapter.observation.Product
import com.team376.pulsemetry.telemetry.adapter.observation.profile.LogProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.LogRecordView
import com.team376.pulsemetry.telemetry.adapter.observation.profile.MappedEvent
import com.team376.pulsemetry.telemetry.adapter.observation.profile.MetricProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.ProductProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.SpanProfile
import com.team376.pulsemetry.telemetry.adapter.observation.profile.VersionSet

/**
 * 확인한 버전의 api_request만 지원한다(ADR 0020 §4·부록 C).
 * 2.1.283·284·285는 9월 원천의 구조 통계와 공식 성분 정의, 2.1.290은 실캡처 8건이 근거다.
 * `otlp-v2/claude_code/PROFILE-EVIDENCE.md` §10·11. 다른 로그·스팬·메트릭까지 넓히지 않는다.
 */
public object ClaudeApiProfile : ProductProfile {

	override val product: Product = Product.CLAUDE_CODE
	override val versions: VersionSet = VersionSet.exactly("2.1.283", "2.1.284", "2.1.285", "2.1.290")
	override val mappingVersion: String = "claude-code-api-v1"
	override val logs: LogProfile = ApiLogs
	override val spans: SpanProfile? = null
	override val metrics: MetricProfile? = null

	private object ApiLogs : LogProfile {
		override val allowlist: MetadataAllowlist = MetadataAllowlist(
			version = "claude-code-api-logs-v1",
			resource = setOf("service.name", "service.version", "telemetry.sdk.name", "telemetry.sdk.language", "telemetry.sdk.version"),
			record = setOf(
				"event.name", "event.timestamp", "event.sequence", "session.id", "prompt.id", "model",
				"request_id", "client_request_id", "input_tokens", "output_tokens", "cache_read_tokens",
				"cache_creation_tokens", "cost_usd", "cost_usd_micros", "duration_ms", "ttft_ms", "query_source",
			),
		)

		override fun semanticName(view: LogRecordView): String? = ClaudeCodeLogs.semanticName(view)

		override fun map(name: String, view: LogRecordView, base: EventObservation): MappedEvent? {
			if (name != "api_request") return null
			// 기존 wire·본문 이름 충돌·음수·비용 충돌 검사와 확인된 exclusive 의미를 적용한다.
			return ClaudeCodeLogs.map(name, view, base)
		}
	}
}
