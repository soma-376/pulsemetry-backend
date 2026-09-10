package com.team376.pulsemetry.dashboard

import org.springframework.core.io.ClassPathResource
import com.team376.pulsemetry.security.user.UserAuthException
import org.springframework.web.bind.annotation.*
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/** 카탈로그 가용성은 데이터 산출 가능성이다. 실행 완료 여부나 관측 결과를 나타내지 않는다. */
@Component
class DashboardScenarioCatalog(private val mapper: ObjectMapper, private val metrics: DashboardMetricCatalog) {
    private val catalog = ClassPathResource("dashboard/scenarios.json").inputStream.use { mapper.readTree(it) }
    private val items = catalog["items"].toList()
    private val byId = items.associateBy { it["scenario_id"].asString() }
    private val categories = catalog["categories"].toList().map { it["id"].asString() }.toSet()

    init {
        require(items.size == 46 && byId.size == items.size && categories.size == 8)
        require(items.all { item -> item["metric_ids"].all { metrics.find(it.asString()) != null } })
    }

    fun list(category: String?, availability: String?, targetPage: String?, q: String): Map<String, Any> {
        require(category == null || category in categories)
        require(availability == null || availability in setOf("available", "partial", "unavailable"))
        require(targetPage == null || targetPage in setOf("P1", "P2", "P3", "P4", "P5"))
        require(q.length <= 500)
        val filtered = items.filter {
            (category == null || it["category"].asString() == category) &&
                (availability == null || it["availability"].asString() == availability) &&
                (targetPage == null || it["target_page"].asString() == targetPage) &&
                listOf("title", "situation").any { field -> it.path(field).asString("").contains(q.trim(), ignoreCase = true) }
        }.map { item ->
            (item.deepCopy() as ObjectNode).also { it.remove(listOf("params_schema", "findings_rules", "actions")) }
        }
        return mapOf("categories" to catalog["categories"], "items" to filtered)
    }

    fun detail(scenarioId: String): JsonNode {
        val item = byId[scenarioId] ?: throw UserAuthException("not_found", 404)
        return (item.deepCopy() as ObjectNode).also { detail ->
            detail.set("metrics", mapper.valueToTree<JsonNode>(item["metric_ids"].toList().map {
                val metric = requireNotNull(metrics.find(it.asString()))
                mapOf("metric_id" to metric.metricId, "indicator_id" to metric.indicatorId,
                    "availability" to metric.availability, "definition" to metric.definition, "caveat" to metric.caveat)
            }))
        }
    }
}

@RestController
@RequestMapping("/v1/scenarios")
class DashboardScenarios(private val catalog: DashboardScenarioCatalog) {
    @GetMapping
    fun list(@RequestParam(required = false) category: String?,
        @RequestParam(required = false) availability: String?,
        @RequestParam(name = "target_page", required = false) targetPage: String?,
        @RequestParam(defaultValue = "") q: String) = catalog.list(category, availability, targetPage, q)

    @GetMapping("/{scenarioId}")
    fun detail(@PathVariable scenarioId: String) = catalog.detail(scenarioId)
}
