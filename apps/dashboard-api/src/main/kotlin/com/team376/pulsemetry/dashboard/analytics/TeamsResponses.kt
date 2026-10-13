package com.team376.pulsemetry.dashboard.analytics

import com.team376.pulsemetry.dashboard.analytics.OverviewResponse.Value
import com.team376.pulsemetry.dashboard.analytics.OverviewResponse.Metric

/** 팀 분석 명세의 타입. 이름·타입·nullable 은 요청서의 TypeScript 타입과 같다. */
data class TeamModelUsage(
	val modelId: String,
	val displayName: String,
	val equivalentCostUsd: String?,
	val totalTokens: Long?,
	val status: String,
	val reasons: List<String>,
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
	val status: String,
	val reasons: List<String>,
)

/** 팀 식별자와 화면 지표·모델·추이. 미배정은 [teamId] 가 null 이다. */
data class TeamAnalytics(
	val teamId: String?,
	val teamName: String,
	val metrics: TeamMetrics,
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
	val status: String,
	val reasons: List<String>,
)

data class ModelScatter(
	val teamUsageCostShareThreshold: Double,
	val models: List<ScatterModel>,
)

data class TeamsResponse(
	val meta: TeamMeta,
	val comparison: AnalyticsComparison,
	val attributionBasis: String,
	val totals: TeamMetrics,
	val sort: String,
	val teams: Page<TeamAnalytics>,
	val unassigned: TeamAnalytics,
	val modelScatter: Section<ModelScatter>,
)

data class TeamDetailResponse(
	val meta: TeamMeta,
	val comparison: AnalyticsComparison,
	val team: TeamAnalytics,
)

data class TeamRef(val teamId: String?, val teamName: String)

data class TeamUser(
	val memberId: String,
	val account: String,
	val usage: TeamUserUsage,
	val mainModel: ModelRef?,
	val cacheHitRatio: Value<Double>,
	val lastUsedAt: String?,
)

data class ModelRef(val modelId: String, val displayName: String)

data class TeamUserUsage(val sessionCount: Value<Long>, val totalTokens: Value<Long>, val equivalentCostUsd: Value<String>)

data class TeamUsersSummary(
	val usage: TeamSummaryUsage,
	val averageEquivalentCostUsd: String?,
	val cacheHitRatio: Value<Double>,
	val unidentifiedEquivalentCostUsd: String?,
)

data class TeamUsersResponse(
	val meta: TeamMeta,
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

/** 화면의 팀 비교 표·상세 카드에서 사용하는 값. 이전 값은 각 비교에만 포함한다. */
data class TeamMetrics(
 val activeUsers: Value<Long>, val equivalentCostUsd: Metric<String>, val totalTokens: Metric<Long>, val sessionCount: Metric<Long>,
 val equivalentCostPerUserUsd: Metric<String>, val equivalentCostPerSessionUsd: Value<String>,
 val tokensPerUser: Value<Double>, val sessionsPerUser: Value<Double>, val tokensPerSession: Value<Double>,
 val effectiveCostPerMillionTokensUsd: Value<String>,
)
data class TeamSummaryUsage(val activeUsers: Value<Long>, val sessionCount: Value<Long>, val totalTokens: Value<Long>, val equivalentCostUsd: Value<String>)
data class TeamMeta(
 val organizationId: String, val startDate: String, val endDate: String, val timeZone: String,
 val dayCount: Int, val dataThrough: String?, val dataState: String, val currentCoverage: Coverage, val snapshotId: String,
) {
 companion object { fun of(meta: AnalyticsMeta) = TeamMeta(meta.organizationId, meta.startDate, meta.endDate, meta.timeZone,
 meta.dayCount, meta.dataThrough, meta.dataState, meta.currentCoverage, meta.snapshotId) }
}
