package com.team376.pulsemetry.dashboard.analytics

/**
 * 화면 요청서의 공통 타입(개요 명세 3절). 이름·타입·nullable 은 요청서의 TypeScript 타입과 같다 — 필드를 더하거나 의미를 바꾸지 않는다.
 * `Money` 는 [Money.format] 의 문자열이다.
 */
data class Coverage(
	/** `complete` · `partial` · `none`. v1 은 완전성의 근거가 없어 `complete` 를 내지 않는다. */
	val status: String,
	val observedDays: Int,
) {
	companion object {
		fun of(observedDays: Int) = Coverage(if (observedDays > 0) PARTIAL else NONE, observedDays)

		const val PARTIAL = "partial"
		const val NONE = "none"
	}
}

data class Tokens(
	val inputUncached: Long?,
	val output: Long?,
	val cacheRead: Long?,
	val cacheWrite: Long?,
	val total: Long?,
)

data class Usage(
	val activeUsers: Long?,
	val sessionCount: Long?,
	val tokens: Tokens,
	val equivalentCostUsd: String?,
) {
	companion object {
		/** 공통 계산기의 null 규칙을 거친 값. */
		fun of(totals: UsageTotals, pricingMixed: Boolean) = Usage(
			activeUsers = totals.activeUsers(),
			sessionCount = totals.sessionCount(),
			tokens = Tokens(
				inputUncached = totals.tokens(totals.inputUncached),
				output = totals.tokens(totals.output),
				cacheRead = totals.tokens(totals.cacheRead),
				cacheWrite = totals.tokens(totals.cacheWrite),
				total = totals.apiTotal(),
			),
			equivalentCostUsd = totals.equivalentCost(pricingMixed)?.let(Money::format),
		)
	}
}

data class TeamPeriod(
	val activeUsers: Long?,
	val equivalentCostUsd: String?,
) {
	companion object {
		fun of(totals: UsageTotals, pricingMixed: Boolean) =
			TeamPeriod(totals.activeUsers(), totals.equivalentCost(pricingMixed)?.let(Money::format))
	}
}

data class TopModel(
	val modelId: String,
	val displayName: String,
	val share: Double,
)

/** 요청서의 `Availability` 와 기존 사유 어휘(합의 전에는 이것만 쓴다). */
object Availability {
	const val AVAILABLE = "available"
	const val PARTIAL = "partial"
	const val UNAVAILABLE = "unavailable"

	const val SOURCE_NOT_AVAILABLE = "source_not_available"
	const val NOT_APPLICABLE = "not_applicable"
	const val EVALUATION_NOT_CONFIGURED = "evaluation_not_configured"
	const val METHODOLOGY_NOT_AVAILABLE = "methodology_not_available"
}

/** 모델 ID 의 표시 이름 — 표시 사전이 없어 ID 의 마지막 성분(모델명)을 되돌린다. 모델명이 없던 행(`~`)은 `unknown` 이다. */
object ModelNames {
	fun displayName(modelId: String): String {
		val last = modelId.substringAfterLast('/')
		if (last == "~") return "unknown"
		return last.replace("%2F", "/").replace("%7E", "~").replace("%25", "%")
	}
}
