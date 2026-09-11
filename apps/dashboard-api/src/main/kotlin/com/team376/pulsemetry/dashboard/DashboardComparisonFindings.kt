package com.team376.pulsemetry.dashboard

import tools.jackson.databind.JsonNode

/** 전후 기간의 관측 차이만 제공한다. 교육·정책의 인과 효과를 판정하지 않는다. */
internal object DashboardComparisonFindings {
    fun evaluate(scenario: String, results: Map<String,JsonNode>, periods: Map<String,String>,
        complete: Boolean): List<Map<String,Any>> {
        if (!complete) return emptyList()
        return results.flatMap { (metric,result) -> result["frames"].toList().mapNotNull { frame ->
            val fields = frame["schema"]["fields"].toList()
            val name = if (metric in setOf("prompts_per_session","gate_wait_ms")) "p50" else "value"
            val current = fields.indexOfFirst { it["name"].asString()==name }
            val prior = fields.indexOfFirst { it["name"].asString()=="${name}_compare" }
            if (current<0 || prior<0 || listOf(current,prior).any {
                    fields[it].path("config").path("suppressed").asBoolean(false) }) return@mapNotNull null
            val after = frame["data"]["values"][current].firstOrNull()
            val before = frame["data"]["values"][prior].firstOrNull()
            if (after==null || before==null || !after.isNumber || !before.isNumber ||
                !after.asDouble().isFinite() || !before.asDouble().isFinite()) return@mapNotNull null
            val delta = after.asDouble()-before.asDouble()
            if (!delta.isFinite() || delta==0.0) return@mapNotNull null
            mapOf("rule_id" to "observed_period_change","severity" to "info",
                "widget_id" to if(scenario=="S6-3") "W3.1" else if(scenario in setOf("S5-6","S8-6")) "W3.3" else when(metric) {
                    "active_users","adoption_rate" -> "W2.0"; "prompts_per_session","usage_concentration" -> "W2.2"; else -> "W2.8" },
                "title" to "기준일 전후의 관측값 차이가 있습니다",
                "evidence" to (periods + mapOf("metric_id" to metric,"statistic" to name,
                    "before" to before.asDouble(),"after" to after.asDouble(),"delta" to delta,
                    "dimensions" to fields[current].path("labels").properties().associate { it.key to it.value.asString() },
                    "limitation" to (if(scenario=="S5-6") "config·hook 결정만 포함합니다. 전후 기간 길이가 다를 수 있어 건수 차이를 발생률 변화로 해석하지 않습니다. " else "") + "기간 전체의 관측 집계 차이입니다. 구성원 변화·수집 누락을 통제하지 않으며 기준일 변경의 인과 효과나 개선 여부를 판정하지 않습니다.")))
        } }
    }
}
