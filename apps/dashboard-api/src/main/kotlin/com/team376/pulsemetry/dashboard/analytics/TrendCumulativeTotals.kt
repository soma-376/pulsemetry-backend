package com.team376.pulsemetry.dashboard.analytics

/** 가산 가능한 성분만 누적한다. 세션은 별도 최초 등장 집계, 토큰 의미는 집합의 합집합이다. */
class TrendCumulativeTotals {
	var totals: UsageTotals = UsageTotals.EMPTY
		private set
	fun add(bucket: UsageTotals, firstSessions: Long): UsageTotals {
		val previous = totals
		val groups = previous.semanticsGroups + bucket.semanticsGroups
		totals = UsageTotals(
			usageRows = previous.usageRows + bucket.usageRows,
			identifiedUsers = 0, // 추이에 쓰지 않는 distinct 구성원 수는 합산하지 않는다.
			unidentifiedRows = previous.unidentifiedRows + bucket.unidentifiedRows,
			identifiedSessions = previous.identifiedSessions + firstSessions,
			sessionlessRows = previous.sessionlessRows + bucket.sessionlessRows,
			inputUncached = merge(previous.inputUncached, bucket.inputUncached),
			output = merge(previous.output, bucket.output),
			cacheRead = merge(previous.cacheRead, bucket.cacheRead),
			cacheWrite = merge(previous.cacheWrite, bucket.cacheWrite),
			totalDerived = merge(previous.totalDerived, bucket.totalDerived),
			apiTotalMissingRows = previous.apiTotalMissingRows + bucket.apiTotalMissingRows,
			unverifiedRows = previous.unverifiedRows + bucket.unverifiedRows,
			semanticsProfiles = groups.size.toLong(),
			cost = if (previous.cost == null) bucket.cost else if (bucket.cost == null) previous.cost else previous.cost + bucket.cost,
			unpricedRows = previous.unpricedRows + bucket.unpricedRows,
			multiTeamRows = previous.multiTeamRows + bucket.multiTeamRows,
			lastSourceTime = listOfNotNull(previous.lastSourceTime, bucket.lastSourceTime).maxOrNull(),
			semanticsGroups = groups,
		)
		return totals
	}
	private fun merge(a: UsageTotals.Component, b: UsageTotals.Component) = UsageTotals.Component(
		if (a.sum == null) b.sum else if (b.sum == null) a.sum else a.sum + b.sum, a.missingRows + b.missingRows,
	)
}
