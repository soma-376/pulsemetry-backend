package com.team376.pulsemetry.dashboard.analytics

/** 팀 분석 명세의 타입. 이름·타입·nullable 은 요청서의 TypeScript 타입과 같다. */
data class TeamModelUsage(
	val modelId: String,
	val displayName: String,
	val equivalentCostUsd: String?,
	val totalTokens: Long?,
)

data class TeamModelMix(
	val sessionMixAvailable: Boolean,
	val sessionMixReason: String,
	val models: List<TeamModelUsage>,
) {
	companion object {
		/** 한 세션이 여러 모델을 쓰면 모델 세션 수의 합이 팀 세션 수를 넘는다 — v1 은 세션 축 모델 믹스를 지원하지 않는다. */
		fun of(models: List<TeamModelUsage>) = TeamModelMix(false, "multi_model_sessions", models)
	}
}

data class TeamTrendPoint(
	val date: String,
	val observation: String,
	val equivalentCostUsd: String?,
	val totalTokens: Long?,
	/**
	 * 기간 시작일부터 그날까지 그 팀에서 관측된 고유 세션 수(대시보드 명세 "팀 누적 세션"). 세션은 처음 본 날에 한 번만 더한다.
	 * 시작일부터 그날까지 모든 날이 완전 관측(ADR 0042)이고 세션 없는 사용 행이 없을 때만 값이 있다 — 한 번 끊기면 그 뒤로는 없다.
	 */
	val cumulativeSessionCount: Long?,
)

/** `TeamRef & { current, previous, modelMix, trend }`. 미배정은 [teamId] 가 null 이다. */
data class TeamAnalytics(
	val teamId: String?,
	val teamName: String,
	val current: Usage?,
	val previous: Usage?,
	val modelMix: Section<TeamModelMix>,
	val trend: List<TeamTrendPoint>,
	/** 현재 기간의 카탈로그 제품별 사용(가산 — ADR 0045). 사용량 행이 있는 제품만. */
	val products: List<ProductUsage>,
)

data class ScatterModel(
	val modelId: String,
	val displayName: String,
	val equivalentCostUsd: String?,
	val totalTokens: Long?,
	val usingTeamCount: Long?,
)

data class ModelScatter(
	val teamUsageCostShareThreshold: Double,
	val models: List<ScatterModel>,
)

data class TeamsResponse(
	val meta: AnalyticsMeta,
	val comparison: OverviewResponse.Comparison,
	val ingest: OverviewResponse.Ingest,
	val attributionBasis: String,
	val totals: OverviewResponse.UsagePair,
	val sort: String,
	val teams: Page<TeamAnalytics>,
	val unassigned: TeamAnalytics,
	val modelScatter: Section<ModelScatter>,
)

data class TeamDetailResponse(
	val meta: AnalyticsMeta,
	val comparison: OverviewResponse.Comparison,
	val team: TeamAnalytics,
)

data class TeamRef(val teamId: String?, val teamName: String)

data class TeamUser(
	val memberId: String,
	val account: String,
	val usage: Usage,
	val mainModel: ModelRef?,
	val cache: CacheUsage,
	val lastUsedAt: String?,
)

data class ModelRef(val modelId: String, val displayName: String)

data class CacheUsage(val readTokens: Long?, val eligibleInputTokens: Long?, val hitRatio: Double?)

data class TeamUsersSummary(
	val usage: Usage?,
	val averageEquivalentCostUsd: String?,
	val cacheReadTokens: Long?,
	val cacheEligibleInputTokens: Long?,
	val cacheHitRatio: Double?,
	val unidentifiedEquivalentCostUsd: String?,
)

data class TeamUsersResponse(
	val meta: AnalyticsMeta,
	val team: TeamRef,
	val summary: TeamUsersSummary,
	val users: Page<TeamUser>,
)

/** 요청서의 `CurrentMeta` — 선택 기간과 무관한 현재 상태의 meta. */
data class CurrentMeta(
	val organizationId: String,
	val generatedAt: String,
	val asOf: String,
	val snapshotId: String,
	val currency: String,
	val timeZone: String,
)

data class DirectoryTeam(val teamId: String, val teamName: String, val version: Long)

data class TeamDirectoryResponse(val meta: CurrentMeta, val teams: Page<DirectoryTeam>)
