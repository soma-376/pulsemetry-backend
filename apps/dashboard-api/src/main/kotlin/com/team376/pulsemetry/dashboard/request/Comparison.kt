package com.team376.pulsemetry.dashboard.request

/**
 * 비교 방식 — 개요·팀 분석만 받는다. 기본은 [PREV_WEEK].
 *
 * - [PREV_WEEK] 선택 기간의 양 끝을 7일 앞으로 옮긴다. 길이는 같고, 7일보다 길면 현재 기간과 겹친다.
 * - [PREV_PERIOD] 선택한 N일 바로 앞의 N일.
 * - [NONE] 비교하지 않는다.
 */
enum class CompareMode(val wire: String) {
	PREV_WEEK("prev_week"),
	PREV_PERIOD("prev_period"),
	NONE("none"),
	;

	/** [current] 의 비교 기간. [NONE] 이면 없다. */
	fun periodFor(current: DatePeriod): DatePeriod? = when (this) {
		PREV_WEEK -> current.shiftDays(-WEEK_DAYS)
		PREV_PERIOD -> current.shiftDays(-current.days.toLong())
		NONE -> null
	}

	companion object {
		private const val WEEK_DAYS = 7L

		val BY_WIRE: Map<String, CompareMode> = entries.associateBy { it.wire }
	}
}

/** 현재 기간과 그 비교 기간. [previous] 는 [mode] 가 `none` 일 때만 없다. */
data class ComparedPeriod(
	val current: DatePeriod,
	val mode: CompareMode,
) {
	val previous: DatePeriod? get() = mode.periodFor(current)
}
