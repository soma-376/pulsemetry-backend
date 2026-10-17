package com.team376.pulsemetry.dashboard.request

/** 고정 snapshot의 정렬된 목록에서 건너뛸 행 수. */
data class OffsetPageRequest(val limit: Int, val offset: Int = 0)
