package com.team376.pulsemetry.dashboard.analytics

/**
 * 비교를 공개해도 되는가. 개요 명세 4절 — "두 기간의 관측이 충분하고 비교할 수 있다"일 때만 `available` 이다.
 *
 * 기본 정책은 **두 기간 모두 완전 관측**이다. v1 은 완전성의 근거(설치 heartbeat 등)가 없어 [Coverage] 가 `complete` 가 되지 않으므로
 * 비교는 공개되지 않는다(`unavailable` + `source_not_available`, previous 전부 null). 비교 기간의 계산 경로는 그대로 있어, 근거가 생기면
 * 이 판정만으로 열린다.
 */
fun interface ComparisonPolicy {

	fun comparable(current: Coverage, previous: Coverage): Boolean

	companion object {
		val COMPLETE_ONLY = ComparisonPolicy { current, previous -> current.status == COMPLETE && previous.status == COMPLETE }

		private const val COMPLETE = "complete"
	}
}
