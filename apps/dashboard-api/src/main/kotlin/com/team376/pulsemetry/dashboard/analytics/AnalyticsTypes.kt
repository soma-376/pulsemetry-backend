package com.team376.pulsemetry.dashboard.analytics

import java.math.BigDecimal
import java.time.LocalDate

/**
 * 화면 요청서의 공통 타입(개요 명세 3절). 이름·타입·nullable 은 요청서의 TypeScript 타입과 같다 — 필드를 더하거나 의미를 바꾸지 않는다.
 * `Money` 는 [Money.format] 의 문자열이다.
 */
data class Coverage(
	/** `complete` · `partial` · `none`. `complete` 는 기간의 모든 날짜가 완전 관측일 때뿐이다(ADR 0042). */
	val status: String,
	/** 관측이 있었거나 완전한 날짜 수. 이 숫자만으로 완전성을 판정하지 않는다. */
	val observedDays: Int,
) {
	companion object {
		/** [dates] 가 기간의 날짜, [observed] 가 관측이 있는 날짜, [complete] 가 완전 관측으로 판정한 날짜다. */
		fun of(dates: List<LocalDate>, observed: Set<LocalDate>, complete: Set<LocalDate>): Coverage {
			val seen = dates.count { it in observed || it in complete }
			val status = when {
				dates.isNotEmpty() && dates.all { it in complete } -> COMPLETE
				seen > 0 -> PARTIAL
				else -> NONE
			}
			return Coverage(status, seen)
		}

		const val COMPLETE = "complete"
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
		/** 완전 관측인데 사용이 없었다 — 실제 0 이다(ADR 0042). */
		val ZERO = Usage(0, 0, Tokens(0, 0, 0, 0, 0), Money.format(BigDecimal.ZERO))

		/**
		 * 공통 계산기의 null 규칙을 거친 값. [complete] 는 이 값의 기간이 완전 관측이라는 뜻이다 — 그때 사용이 없으면 null 이 아니라 0 이다.
		 * 사용이 있으면 [complete] 와 무관하게 같은 규칙이다(의미가 섞인 토큰·가격 없는 행은 완전해도 null).
		 */
		fun of(totals: UsageTotals, pricingMixed: Boolean, complete: Boolean = false) = if (complete && !totals.hasUsage) ZERO else Usage(
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
		/** [complete] 인 기간에 사용이 없으면 0 이다([Usage.of] 와 같은 규칙). */
		fun of(totals: UsageTotals, pricingMixed: Boolean, complete: Boolean = false) =
			if (complete && !totals.hasUsage) TeamPeriod(0, Money.format(BigDecimal.ZERO))
			else TeamPeriod(totals.activeUsers(), totals.equivalentCost(pricingMixed)?.let(Money::format))
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
