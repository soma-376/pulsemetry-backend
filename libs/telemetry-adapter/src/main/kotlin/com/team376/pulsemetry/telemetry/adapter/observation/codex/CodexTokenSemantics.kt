package com.team376.pulsemetry.telemetry.adapter.observation.codex

import com.team376.pulsemetry.telemetry.adapter.observation.EventObservation
import com.team376.pulsemetry.telemetry.adapter.observation.EventType
import com.team376.pulsemetry.telemetry.adapter.observation.InputSemantics
import com.team376.pulsemetry.telemetry.adapter.observation.MappingStatus
import com.team376.pulsemetry.telemetry.adapter.observation.OutputSemantics
import com.team376.pulsemetry.telemetry.adapter.observation.Product
import com.team376.pulsemetry.telemetry.adapter.observation.UsageRole
import com.team376.pulsemetry.telemetry.adapter.observation.semantics.CacheWriteStatus
import com.team376.pulsemetry.telemetry.adapter.observation.semantics.SemanticsProfile
import com.team376.pulsemetry.telemetry.adapter.observation.semantics.SemanticsScope

/**
 * Responses 완료 사용량 계약의 토큰 포함관계(ADR 0061·0074 · `otlp-v2/codex/PROFILE-EVIDENCE.md`).
 * input에는 cache read/write가, output에는 reasoning이 포함된다. `tool_token_count`는 total이며 tool 성분이 아니다.
 * 실제 공급자는 추정하지 않는다. 미보고 성분을 채우지 않고 producer가 보낸 0과 null을 그대로 구분한다.
 */
internal object CodexTokenSemantics {

	private val responses = SemanticsProfile(
		id = "codex-responses-inclusive-v1",
		inputSemantics = InputSemantics.INCLUSIVE_CACHE,
		outputSemantics = OutputSemantics.INCLUSIVE_REASONING_TOOL,
		cacheWrite = CacheWriteStatus.REPORTED,
		scope = SemanticsScope(
			product = Product.CODEX,
			producerVersions = "Codex Responses 완료 이벤트 계약 — producer 버전 무관",
			provider = "unknown — Responses usage 계약의 producer 보고값",
			configuration = "등록된 Codex 제품의 codex.sse_event / response.completed 사용량; 표면·source emitter 무관",
		),
		evidencePath = "libs/telemetry-adapter/src/test/resources/otlp-v2/codex/PROFILE-EVIDENCE.md#10-2026-10-16-responses-토큰-의미-검증",
	)

	fun forCompleted(observation: EventObservation): SemanticsProfile? {
		val envelope = observation.envelope
		if (envelope.product != Product.CODEX || envelope.mappingStatus != MappingStatus.MAPPED ||
			envelope.usageRole != UsageRole.PRIMARY || observation.eventType != EventType.MODEL_RESPONSE_USAGE
		) return null
		return responses
	}
}
