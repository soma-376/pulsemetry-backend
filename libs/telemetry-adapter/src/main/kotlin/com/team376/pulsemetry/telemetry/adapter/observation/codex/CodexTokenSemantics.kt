package com.team376.pulsemetry.telemetry.adapter.observation.codex

import com.team376.pulsemetry.telemetry.adapter.observation.EventObservation
import com.team376.pulsemetry.telemetry.adapter.observation.EventType
import com.team376.pulsemetry.telemetry.adapter.observation.InputSemantics
import com.team376.pulsemetry.telemetry.adapter.observation.MappingStatus
import com.team376.pulsemetry.telemetry.adapter.observation.OutputSemantics
import com.team376.pulsemetry.telemetry.adapter.observation.Product
import com.team376.pulsemetry.telemetry.adapter.observation.Surface
import com.team376.pulsemetry.telemetry.adapter.observation.UsageRole
import com.team376.pulsemetry.telemetry.adapter.observation.semantics.CacheWriteStatus
import com.team376.pulsemetry.telemetry.adapter.observation.semantics.SemanticsProfile
import com.team376.pulsemetry.telemetry.adapter.observation.semantics.SemanticsScope

/**
 * 확인한 Responses 완료 emitter의 토큰 포함관계(ADR 0061 · `otlp-v2/codex/PROFILE-EVIDENCE.md` §10).
 * input에는 cache read/write가, output에는 reasoning이 포함된다. `tool_token_count`는 total이며 tool 성분이 아니다.
 * 실제 공급자는 추정하지 않는다. 미보고 성분을 채우지 않고 producer가 보낸 0과 null을 그대로 구분한다.
 */
internal object CodexTokenSemantics {

	private const val EMITTER = "event otel/src/events/session_telemetry.rs"
	private val locations = mapOf(
		"0.153.4" to "$EMITTER:1012",
		"0.154.0-alpha.6.2" to "$EMITTER:1012",
		"0.155.0-alpha.2.6" to "$EMITTER:1035",
		"0.155.0-alpha.9.2" to "$EMITTER:1035",
		"0.158.0-alpha.2.1" to "$EMITTER:1035",
		"0.159.2" to "$EMITTER:1103",
		"0.162.0-alpha.17.2" to "$EMITTER:1132",
		"0.165.0" to "$EMITTER:1132",
	)
	private val appServerOnly = setOf("0.158.0-alpha.2.1", "0.159.2", "0.162.0-alpha.17.2")
	private val desktopOnly = setOf("0.165.0")

	private val responses = SemanticsProfile(
		id = "codex-responses-inclusive-v1",
		inputSemantics = InputSemantics.INCLUSIVE_CACHE,
		outputSemantics = OutputSemantics.INCLUSIVE_REASONING_TOOL,
		cacheWrite = CacheWriteStatus.REPORTED,
		scope = SemanticsScope(
			product = Product.CODEX,
			producerVersions = locations.keys.joinToString(", "),
			provider = "unknown — Responses usage 계약의 producer 보고값",
			configuration = "버전별 완료 emitter; 기존 네 버전 CLI/APP_SERVER, 추가 세 버전 APP_SERVER, Desktop은 0.153.4·0.154.0-alpha.6.2·0.165.0만",
		),
		evidencePath = "libs/telemetry-adapter/src/test/resources/otlp-v2/codex/PROFILE-EVIDENCE.md#10-2026-10-16-responses-토큰-의미-검증",
	)

	fun forCompleted(observation: EventObservation, eventName: String): SemanticsProfile? {
		val envelope = observation.envelope
		if (envelope.product != Product.CODEX || envelope.mappingStatus != MappingStatus.MAPPED ||
			envelope.usageRole != UsageRole.PRIMARY || observation.eventType != EventType.MODEL_RESPONSE_USAGE
		) return null
		if (envelope.surface == Surface.DESKTOP) {
			if (envelope.serviceVersion !in CodexDesktopCompletedProfile.supportedVersions) return null
		} else if (envelope.surface !in setOf(Surface.APP_SERVER, Surface.CLI)) return null
		if (envelope.serviceVersion in appServerOnly && envelope.surface != Surface.APP_SERVER) return null
		if (envelope.serviceVersion in desktopOnly && envelope.surface != Surface.DESKTOP) return null
		if (!isCompletedEmitter(envelope.serviceVersion, eventName)) return null
		return responses
	}

	fun isCompletedEmitter(version: String?, eventName: String): Boolean = locations[version] == eventName
}
