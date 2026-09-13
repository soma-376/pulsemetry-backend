package com.team376.pulsemetry.dashboard.finding

import tools.jackson.databind.JsonNode

/** 제품별 관측 비용만 안내한다. 제품 이름에서 공급자나 계약 종속성을 추론하지 않는다. */
internal object DashboardVendorFindings {
    fun evaluate(result: JsonNode): List<Map<String,Any>> = result["frames"].toList().mapNotNull { frame ->
        val fields = frame["schema"]["fields"].toList()
        val index = fields.indexOfFirst { it["name"].asString()=="value" }
        if(index<0) return@mapNotNull null
        val field = fields[index]
        val product = field.path("labels").path("product").asString("")
        val cell = frame["data"]["values"][index][0]
        if(product.isBlank() || product=="__other__" || field.path("config").path("suppressed").asBoolean(false) ||
            cell==null || !cell.isNumber || !cell.asDouble().isFinite() || cell.asDouble()<=0) null
        else mapOf("rule_id" to "observed_product_cost", "severity" to "info", "widget_id" to "W1.4",
            "title" to "제품별 비용이 관측되었습니다",
            "evidence" to mapOf("product" to product,"cost_usd" to cell.asDouble(),
                "limitation" to "관측 제품별 비용입니다. 모델 공급자·계약·전환 비용을 확인하지 않으며 벤더 종속 위험이나 숨겨진 그룹의 비중을 판정하지 않습니다."))
    }.sortedBy { (it.getValue("evidence") as Map<*,*>)["product"].toString() }
}
