package com.team376.pulsemetry.dashboard.query

import com.team376.pulsemetry.dashboard.query.DashboardQuerySql.base
import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.JsonNode
import java.util.UUID

data class DashboardQueryFilters(
    @get:JsonProperty("team_ids") val teamIds: Set<UUID> = emptySet(),
    val products: Set<String> = emptySet(), val models: Set<String> = emptySet(),
    @get:JsonProperty("member_ids") val memberIds: Set<UUID> = emptySet(),
)
data class DashboardQueryFilterOverride(
    @get:JsonProperty("team_ids") val teamIds: Set<UUID>? = null,
    val products: Set<String>? = null, val models: Set<String>? = null,
    @get:JsonProperty("member_ids") val memberIds: Set<UUID>? = null,
) {
    fun merge(base: DashboardQueryFilters) = DashboardQueryFilters(teamIds ?: base.teamIds,
        products ?: base.products, models ?: base.models, memberIds ?: base.memberIds)
}
data class DashboardQueryItem(
    @get:JsonProperty("ref_id") val refId: String,
    @get:JsonProperty("metric_id") val metricId: String,
    @get:JsonProperty("group_by") val groupBy: List<String> = emptyList(),
    @get:JsonProperty("frame_type") val frameType: String? = null,
    val interval: String? = null, val source: String? = null, val limit: Int? = null,
    val order: String = "value_desc", val filters: DashboardQueryFilterOverride? = null,
    @get:JsonProperty("price_basis") val priceBasis: String? = null,
    val params: Map<String, JsonNode> = emptyMap(),
)
data class DashboardQueryRequest(
    val from: String, val to: String, val tz: String? = null, val compare: String = "none",
    val filters: DashboardQueryFilters = DashboardQueryFilters(),
    @get:JsonProperty("price_basis") val priceBasis: String = "list",
    @get:JsonProperty("max_data_points") val maxDataPoints: Int = 500,
    val queries: List<DashboardQueryItem>,
)
