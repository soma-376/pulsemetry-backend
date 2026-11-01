package com.team376.pulsemetry.telemetry.adapter.observation

import com.team376.pulsemetry.telemetry.model.EventObservation
import com.team376.pulsemetry.telemetry.model.EventType
import com.team376.pulsemetry.telemetry.model.MappingStatus
import com.team376.pulsemetry.telemetry.model.Product
import com.team376.pulsemetry.telemetry.model.QualityFlag
import com.team376.pulsemetry.telemetry.model.UsageContractOutcome
import com.team376.pulsemetry.telemetry.model.UsageContractResult
import com.team376.pulsemetry.telemetry.model.UsageRole

/** 버전·모델·신원·동적 이벤트 이름 없이 사용량 후보의 계약 불일치를 드러낸다. */
internal object UsageContractDiagnostics {
	private val codexTokens = setOf(
		"input_token_count", "output_token_count", "cached_token_count", "cache_write_token_count", "reasoning_token_count", "tool_token_count",
	)
	private val invalidFlags = setOf(
		QualityFlag.AMBIGUOUS_ATTRIBUTE, QualityFlag.AMBIGUOUS_PAYLOAD, QualityFlag.INVALID_MEASUREMENT,
		QualityFlag.DERIVED_VALUE_INVALID, QualityFlag.NON_FINITE_VALUE,
	)

	fun isCandidate(product: Product, name: String?, attributes: List<TypedAttribute>): Boolean = when (product) {
		Product.CLAUDE_CODE -> name == "api_request"
		Product.CODEX -> name == "codex.sse_event" && attributes.any {
			it.key in codexTokens || (it.key == "event.kind" && it.value == TypedValue.Str("response.completed"))
		}
		Product.UNKNOWN -> false
	}

	fun classify(event: EventObservation): UsageContractResult {
		val envelope = event.envelope
		val outcome = when {
			envelope.mappingStatus in setOf(MappingStatus.AMBIGUOUS, MappingStatus.INVALID) ||
				envelope.qualityFlags.any { it in invalidFlags } -> UsageContractOutcome.INVALID
			envelope.mappingStatus == MappingStatus.MAPPED && envelope.product == Product.CODEX &&
				envelope.attrs["event.kind"] == "response.completed" &&
				listOf(event.tokensInput, event.tokensOutput, event.tokensCacheRead, event.tokensCacheCreate).all { it == null } -> UsageContractOutcome.INCOMPLETE
			envelope.mappingStatus != MappingStatus.MAPPED || event.eventType != EventType.MODEL_RESPONSE_USAGE -> UsageContractOutcome.UNVERIFIED
			listOf(event.tokensInput, event.tokensOutput, event.tokensCacheRead, event.tokensCacheCreate).any { it == null } -> UsageContractOutcome.INCOMPLETE
			envelope.usageRole != UsageRole.PRIMARY || event.semanticsProfile == null -> UsageContractOutcome.UNVERIFIED
			event.tokensTotalDerived == null || event.tokensInputUncached == null -> UsageContractOutcome.INCOMPLETE
			else -> UsageContractOutcome.COMPATIBLE
		}
		return UsageContractResult(envelope.product, outcome)
	}
}
