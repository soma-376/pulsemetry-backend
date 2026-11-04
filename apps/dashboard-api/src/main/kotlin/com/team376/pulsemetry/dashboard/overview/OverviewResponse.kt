package com.team376.pulsemetry.dashboard.overview

import com.team376.pulsemetry.dashboard.analytics.*

/** 화면에 필요한 값만 반환한다. null 영역은 빈 기간 또는 errors에 명시된 조회 실패다. */
data class OverviewResponse(
 val meta: Meta, val comparison: Comparison,
 val summary: Summary?, val trend: Trend?, val modelMix: ModelMix?, val teamUsage: TeamUsage?,
 val errors: List<SectionError>,
) {
 data class Meta(val organizationId: String, val generatedAt: String, val dataThrough: String?, val currency: String,
  val startDate: String, val endDate: String, val timeZone: String, val dayCount: Int, val dataState: String, val currentCoverage: Coverage)
 data class Comparison(val mode: String, val startDate: String?, val endDate: String?, val coverage: Coverage?)
 data class Summary(val activeUsers: Metric<Long>, val equivalentCostUsd: Metric<String>, val sessionCount: Metric<Long>,
  val equivalentCostPerUserUsd: Metric<String>, val totalTokens: Metric<Long>, val sessionsPerUser: Value<Double>, val equivalentCostPerSessionUsd: Value<String>,
  val knownEquivalentCostUsd: Value<String>, val pricedUsageRows: Long, val unpricedUsageRows: Long)
 data class Trend(val bucket: String, val points: List<TrendPoint>)
 data class TrendPoint(val date: String, val observation: String, val equivalentCostUsd: Value<String>, val totalTokens: Value<Long>,
  val knownEquivalentCostUsd: Value<String>, val pricedUsageRows: Long, val unpricedUsageRows: Long)
 data class ModelMix(val models: List<ModelShare>)
 data class ModelShare(val modelId: String, val displayName: String, val equivalentCostUsd: Value<String>, val totalTokens: Value<Long>,
  val effectiveCostPerMillionTokensUsd: Value<String>, val costShare: Value<Double>, val modelVendor: ModelVendors.Vendor?)
 data class TeamUsage(val attributionBasis: String, val totalTeamCount: Int, val topTeams: List<TopTeam>, val unassigned: Unassigned?)
 data class TopTeam(val teamId: String, val teamName: String, val products: List<ProductRef>, val activeUsers: Value<Long>, val equivalentCostUsd: Metric<String>)
 data class Unassigned(val products: List<ProductRef>, val activeUsers: Value<Long>, val equivalentCostUsd: Metric<String>)
 data class SectionError(val section: String, val code: String, val retryable: Boolean)
}
