package com.team376.pulsemetry.dashboard.analytics

/** 다음 위치는 실제 반환한 행 수만큼 전진한다. 마지막 페이지는 null이다. */
data class OffsetPage<T>(val items: List<T>, val totalCount: Int, val nextOffset: Int?) {
	companion object {
		fun <T> of(all: List<T>, offset: Int, limit: Int): OffsetPage<T> {
			val items = all.drop(offset).take(limit)
			val next = if (offset.toLong() + items.size < all.size) offset + items.size else null
			return OffsetPage(items, all.size, next)
		}
	}
}
