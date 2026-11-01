package com.team376.pulsemetry.dashboard.analytics

	enum class UserOrder(val wire: String) {
		ASC("asc"), DESC("desc");

		companion object {
			val BY_WIRE = entries.associateBy { it.wire }
		}
	}
