package com.team376.pulsemetry.telemetry.adapter.observation.semantics

import com.team376.pulsemetry.telemetry.adapter.observation.EventObservation
import com.team376.pulsemetry.telemetry.adapter.observation.EventType
import com.team376.pulsemetry.telemetry.adapter.observation.InputSemantics
import com.team376.pulsemetry.telemetry.adapter.observation.OutputSemantics
import com.team376.pulsemetry.telemetry.adapter.observation.QualityFlag
import com.team376.pulsemetry.telemetry.adapter.observation.withFlags

/**
 * 파생 토큰 — `tokens_input_uncached`·`tokens_total_derived`(ADR 0020 §4 의 표).
 *
 * | 프로파일 | `tokens_input_uncached` | `tokens_total_derived` |
 * |---|---|---|
 * | inclusive, write 보고 | input − cache_read − cache_create | input + output |
 * | inclusive, write 해당 없음 | input − cache_read | input + output |
 * | exclusive, write 보고 | input | input + cache_read + cache_create + output |
 * | exclusive, write 해당 없음 | input | input + cache_read + output |
 * | unknown 또는 필요한 성분 미보고 | null | null |
 *
 * - reasoning·tool 은 `output_semantics` 가 output 밖으로 정한 경우에만 total 에 더하고, 그때는 둘 다 보고돼야 한다.
 * - **미보고 cache write 를 0 으로 추정하지 않는다.** write 가 "보고"인 프로파일에서 값이 없으면 계산하지 않는다.
 *   "해당 없음" 프로파일인데 값이 보고됐다면 프로파일이 이 관측에 맞지 않는 것이다 — 파생값 null + `derived_value_invalid`.
 * - 보고 total과 계산 total의 불일치, 포함형 input보다 큰 캐시 성분·output보다 큰 reasoning·tool, overflow·음수 결과는 계약 모순이다.
 *   모순에는 total을 null로 하고 `derived_value_invalid`를 남긴다. 유효한 보고 성분과 uncached는 보존한다(ADR 0074).
 * - 프로파일이 없거나 input 의미가 unknown 이면 사용량 관측에 `usage_semantics_unverified` 를 남긴다.
 */
public object TokenDerivation {

	public fun apply(observation: EventObservation, semantics: SemanticsProfile?): EventObservation {
		val o = observation
		val reported = listOf(o.tokensInput, o.tokensOutput, o.tokensCacheRead, o.tokensCacheCreate, o.tokensReasoning, o.tokensTool)
		val usage = o.eventType == EventType.MODEL_RESPONSE_USAGE
		if (reported.all { it == null } && !usage) return observation

		if (semantics == null) {
			val cleared = o.copy(
				inputSemantics = InputSemantics.UNKNOWN,
				outputSemantics = OutputSemantics.UNKNOWN,
				semanticsProfile = null,
				tokensTotalDerived = null,
				tokensInputUncached = null,
			)
			return if (usage) cleared.withFlags(QualityFlag.USAGE_SEMANTICS_UNVERIFIED) else cleared
		}

		val flags = mutableSetOf<QualityFlag>()
		if (semantics.inputSemantics == InputSemantics.UNKNOWN && usage) flags += QualityFlag.USAGE_SEMANTICS_UNVERIFIED

		val contradiction = semantics.cacheWrite == CacheWriteStatus.NOT_APPLICABLE && o.tokensCacheCreate != null
		val uncached = if (contradiction) Outcome.Invalid else uncached(o, semantics)
		val total = if (contradiction) Outcome.Invalid else total(o, semantics)
		val derivedTotal = (total as? Outcome.Value)?.value
		val totalMismatch = derivedTotal != null && o.tokensTotalReported != null && derivedTotal != o.tokensTotalReported
		// 다른 캐시 성분이 미보고여도 이미 보고된 성분의 포함관계 위반은 확인할 수 있다.
		val inputContradiction = semantics.inputSemantics == InputSemantics.INCLUSIVE_CACHE &&
			o.tokensInput?.let { input ->
				(o.tokensCacheRead?.let { it > input } == true) || (o.tokensCacheCreate?.let { it > input } == true)
			} == true
		val outputContradiction = semantics.outputSemantics == OutputSemantics.INCLUSIVE_REASONING_TOOL &&
			o.tokensOutput?.let { output ->
				(o.tokensReasoning?.let { it > output } == true) || (o.tokensTool?.let { it > output } == true)
			} == true
		val invalid = uncached == Outcome.Invalid || total == Outcome.Invalid || totalMismatch || inputContradiction || outputContradiction
		if (invalid) flags += QualityFlag.DERIVED_VALUE_INVALID

		return o.copy(
			inputSemantics = semantics.inputSemantics,
			outputSemantics = semantics.outputSemantics,
			semanticsProfile = semantics.id,
			tokensInputUncached = (uncached as? Outcome.Value)?.value,
			tokensTotalDerived = if (invalid) null else derivedTotal,
		).withFlags(*flags.toTypedArray())
	}

	private sealed interface Outcome {
		data class Value(val value: Long) : Outcome
		data object NotComputable : Outcome
		data object Invalid : Outcome
	}

	/** cache write 성분 — 보고 프로파일이면 값, 해당 없음이면 0 이 아니라 "항목 없음", 모르면 계산 불가. */
	private fun cacheCreateTerm(o: EventObservation, semantics: SemanticsProfile): Long? = when (semantics.cacheWrite) {
		CacheWriteStatus.REPORTED -> o.tokensCacheCreate
		CacheWriteStatus.NOT_APPLICABLE -> NO_TERM
		CacheWriteStatus.UNKNOWN -> null
	}

	private fun uncached(o: EventObservation, semantics: SemanticsProfile): Outcome {
		val input = o.tokensInput ?: return Outcome.NotComputable
		return when (semantics.inputSemantics) {
			InputSemantics.UNKNOWN -> Outcome.NotComputable
			// input 이 cache read·create 를 모두 뺀 값이다.
			InputSemantics.EXCLUSIVE_CACHE -> Outcome.Value(input)
			InputSemantics.INCLUSIVE_CACHE -> {
				val read = o.tokensCacheRead ?: return Outcome.NotComputable
				val create = cacheCreateTerm(o, semantics) ?: return Outcome.NotComputable
				exact { Math.subtractExact(Math.subtractExact(input, read), if (create == NO_TERM) 0 else create) }
			}
		}
	}

	private fun total(o: EventObservation, semantics: SemanticsProfile): Outcome {
		val input = o.tokensInput ?: return Outcome.NotComputable
		val output = o.tokensOutput ?: return Outcome.NotComputable
		val extras = when (semantics.outputSemantics) {
			OutputSemantics.UNKNOWN -> return Outcome.NotComputable
			OutputSemantics.INCLUSIVE_REASONING_TOOL -> 0L
			OutputSemantics.EXCLUSIVE_REASONING_TOOL -> {
				val reasoning = o.tokensReasoning ?: return Outcome.NotComputable
				val tool = o.tokensTool ?: return Outcome.NotComputable
				exactOrNull { Math.addExact(reasoning, tool) } ?: return Outcome.Invalid
			}
		}
		return when (semantics.inputSemantics) {
			InputSemantics.UNKNOWN -> Outcome.NotComputable
			// 캐시 성분이 이미 input 안에 있다 — 다시 더하지 않는다.
			InputSemantics.INCLUSIVE_CACHE -> exact { Math.addExact(Math.addExact(input, output), extras) }
			InputSemantics.EXCLUSIVE_CACHE -> {
				val read = o.tokensCacheRead ?: return Outcome.NotComputable
				val create = cacheCreateTerm(o, semantics) ?: return Outcome.NotComputable
				exact {
					Math.addExact(Math.addExact(Math.addExact(Math.addExact(input, read), if (create == NO_TERM) 0 else create), output), extras)
				}
			}
		}
	}

	private inline fun exact(block: () -> Long): Outcome {
		val value = exactOrNull(block) ?: return Outcome.Invalid
		return if (value < 0) Outcome.Invalid else Outcome.Value(value)
	}

	private inline fun exactOrNull(block: () -> Long): Long? = try {
		block()
	} catch (_: ArithmeticException) {
		null
	}

	/** "해당 없음"을 0 과 구별하는 표식. 산식에서만 0 항으로 읽는다. */
	private const val NO_TERM: Long = Long.MIN_VALUE
}
