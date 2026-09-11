package com.team376.pulsemetry.dashboard

import tools.jackson.databind.JsonNode

/** 익명 상위 10% 점유율만 설명한다. 명세에 없는 집중 위험 임계값은 만들지 않는다. */
internal object DashboardConcentrationFindings {
    fun evaluate(result: JsonNode): List<Map<String,Any>> = result["frames"].toList().mapNotNull { frame ->
        val fields = frame["schema"]["fields"].toList()
        val index = fields.indexOfFirst { it["name"].asString()=="value" }
        if(index<0) return@mapNotNull null
        val cell = frame["data"]["values"][index][0]
        if(fields[index].path("config").path("suppressed").asBoolean(false) ||
            cell==null || !cell.isNumber || !cell.asDouble().isFinite() || cell.asDouble()<=0) null
        else mapOf("rule_id" to "observed_usage_concentration", "severity" to "info", "widget_id" to "W2.5",
            "title" to "익명 사용량 집중도가 관측되었습니다",
            "evidence" to mapOf("top_decile_share" to cell.asDouble(),
                "limitation" to "관측 인원의 상위 10%(올림)가 사용한 토큰 점유율입니다. 개인 식별과 생산성·의존 위험 판정은 제공하지 않습니다."))
    }
}
