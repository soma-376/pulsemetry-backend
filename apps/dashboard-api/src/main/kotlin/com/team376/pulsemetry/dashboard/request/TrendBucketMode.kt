package com.team376.pulsemetry.dashboard.request

/** 생략한 요청은 일별 계약을 유지한다. 자동 집계는 별도 snapshot 계약으로 구분한다. */
enum class TrendBucketMode(val wire: String) {
	DAY("day"), AUTO("auto");

	companion object {
		val BY_WIRE = entries.associateBy { it.wire }
	}
}
