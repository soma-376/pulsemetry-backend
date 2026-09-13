package com.team376.pulsemetry.dashboard.finding

import tools.jackson.databind.JsonNode

/** 비용 메트릭의 관측 차원만 안내한다. 작업 난이도나 대체 모델의 절감액은 추정하지 않는다. */
internal object DashboardModelEffortFindings {
    fun evaluate(result: JsonNode): List<Map<String,Any>> = result["frames"].toList().mapNotNull { frame ->
        val fields = frame["schema"]["fields"].toList()
        val index = fields.indexOfFirst { it["name"].asString()=="value" }
        if(index<0) return@mapNotNull null
        val field = fields[index]
        val labels = listOf("model","effort","speed").associateWith { field.path("labels").path(it).asString("") }
        val cell = frame["data"]["values"][index][0]
        if(labels.values.any { it=="__other__" } || field.path("config").path("suppressed").asBoolean(false) ||
            cell==null || !cell.isNumber || !cell.asDouble().isFinite() || cell.asDouble()<=0) null
        else mapOf("rule_id" to "observed_model_effort_cost", "severity" to "info", "widget_id" to "W2.5",
            "title" to "모델·effort·speed별 비용이 관측되었습니다",
            "evidence" to (labels + mapOf("cost_usd" to cell.asDouble(),
                "limitation" to "모델·effort와 모델·speed는 같은 delta 비용의 별도 분류이므로 합산하지 않습니다. 빈 차원은 미수집 또는 해당 분류에 없는 축입니다. 작업 난이도·품질·대체 모델 비용이 없어 낭비나 절감액을 판정하지 않습니다.")))
    }.sortedBy { (it.getValue("evidence") as Map<*,*>).let { e -> listOf("model","effort","speed").joinToString("/") { e[it].toString() } } }
}
