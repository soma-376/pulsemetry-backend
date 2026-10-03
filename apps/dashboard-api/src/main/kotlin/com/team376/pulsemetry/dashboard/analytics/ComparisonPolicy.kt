package com.team376.pulsemetry.dashboard.analytics

/**
 * 비교를 공개해도 되는가. 개요 명세 4절 — "두 기간의 관측이 충분하고 비교할 수 있다"일 때만 `available` 이다.
 *
 * 기본 정책은 **두 기간 모두 완전 관측**이다. [Coverage] 는 기간의 모든 날짜가 완전할 때만 `complete` 다 — 완전한 날짜는 설치 보고의
 * 수집 구간으로 판정해 snapshot 에 고정한다(ADR 0042). 한쪽이라도 완전하지 않으면 `unavailable` + `source_not_available`, previous 전부 null 이다.
 */
fun interface ComparisonPolicy {

	fun comparable(current: Coverage, previous: Coverage): Boolean

	companion object {
		val COMPLETE_ONLY = ComparisonPolicy { current, previous -> current.status == COMPLETE && previous.status == COMPLETE }

		private const val COMPLETE = "complete"
	}
}
