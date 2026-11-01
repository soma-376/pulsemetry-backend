package com.team376.pulsemetry.dashboard.analytics

/** 원천 가용성에 따라 부분 값과 미확인 값을 구분한다. */
object AvailabilityValues {
	fun <T> value(value: T?, reasons: List<String> = emptyList()): Value<T> {
		val distinct = reasons.distinct()
		return Value(value, when {
			value == null -> "unavailable"
			distinct.isNotEmpty() -> "partial"
			else -> "complete"
		}, if (value == null && distinct.isEmpty()) listOf("value_unavailable") else distinct)
	}

}
