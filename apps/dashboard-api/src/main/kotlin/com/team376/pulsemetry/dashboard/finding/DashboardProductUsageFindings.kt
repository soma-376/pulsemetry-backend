package com.team376.pulsemetry.dashboard.finding

import tools.jackson.databind.JsonNode

/** 제품별 활성 사용자 수는 중복될 수 있으므로 합산하거나 통합 절감액으로 해석하지 않는다. */
internal object DashboardProductUsageFindings {
    fun evaluate(result: JsonNode): List<Map<String,Any>> = result["frames"].toList().mapNotNull { frame ->
        val fields = frame["schema"]["fields"].toList()
        val index = fields.indexOfFirst { it["name"].asString()=="value" }
        if(index<0) return@mapNotNull null
        val field = fields[index]
        val product = field.path("labels").path("product").asString("")
        val cell = frame["data"]["values"][index][0]
        if(product.isBlank() || product=="__other__" || field.path("config").path("suppressed").asBoolean(false) ||
            cell==null || !cell.isNumber || !cell.asDouble().isFinite() || cell.asDouble()<=0) null
        else mapOf("rule_id" to "observed_product_usage", "severity" to "info", "widget_id" to "W1.1",
            "title" to "제품별 활성 사용자가 관측되었습니다",
            "evidence" to mapOf("product" to product,"active_users" to cell.asDouble(),
                "limitation" to "제품별 활성 사용자 수는 동일 사용자가 중복될 수 있어 합산하지 않습니다. 사용자당 비용은 제품별 값이고 도구 호출은 조회 범위 전체 값이며 제품 간 중복 사용자 수나 통합 절감액을 계산하지 않습니다."))
    }.sortedBy { (it.getValue("evidence") as Map<*,*>)["product"].toString() }
}
