package com.team376.pulsemetry.dashboard

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

/** 정의의 available은 현 스키마의 산출 가능성을 뜻하며 기간 내 관측 여부나 조회 구현 상태가 아니다. */
data class DashboardMetricDefinition(
    @get:JsonProperty("metric_id") val metricId: String,
    @get:JsonProperty("indicator_id") val indicatorId: String,
    val title: String,
    val definition: String,
    val caveat: String,
    val unit: String,
    val availability: String,
    @get:JsonProperty("default_frame_type") val defaultFrameType: String,
    @get:JsonProperty("allowed_group_by") val allowedGroupBy: List<String>,
    @get:JsonProperty("forbidden_group_by") val forbiddenGroupBy: List<String>,
    @get:JsonProperty("min_group_size") val minGroupSize: Int,
    @get:JsonProperty("source_columns") val sourceColumns: List<String>,
    @get:JsonProperty("params_schema") val paramsSchema: Map<String, Any>,
    @get:JsonProperty("sql_template_id") val sqlTemplateId: String,
)

/** QRY가 같은 정의를 참조하도록 지표 메타데이터를 한 곳에서 읽는다. */
@Component
class DashboardMetricCatalog(mapper: ObjectMapper) {
    val items: List<DashboardMetricDefinition> = ClassPathResource("dashboard/metrics.json").inputStream.use {
        mapper.readValue(it, object : TypeReference<List<DashboardMetricDefinition>>() {})
    }
    private val byId = items.associateBy { it.metricId }

    init {
        require(items.isNotEmpty() && byId.size == items.size) { "중복되거나 비어 있는 지표 카탈로그" }
    }

    fun find(metricId: String): DashboardMetricDefinition? = byId[metricId]
}

@RestController
@RequestMapping("/v1/meta")
class DashboardMetrics(private val catalog: DashboardMetricCatalog) {
    @GetMapping("/metrics")
    fun metrics(): Map<String, List<DashboardMetricDefinition>> = mapOf("items" to catalog.items)
}
